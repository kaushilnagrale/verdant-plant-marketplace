package com.verdant.catalog;

import com.verdant.common.testing.AllocationFixtures;
import com.verdant.common.testing.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The seller app's own "Stock adjust" screen (Day 56) - one tap decrement/increment with a reason, real actor recorded. */
@AutoConfigureMockMvc
class ListingStockAdjustTest extends IntegrationTestBase {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    private UUID sellerId;
    private String subjectId;
    private UUID listingId;

    @BeforeEach
    void seed() {
        AllocationFixtures.seedLocalPincodeZone(jdbc);
        sellerId = AllocationFixtures.seedApprovedSeller(jdbc, "Stock Adjust Test Nursery " + UUID.randomUUID());
        subjectId = "sub-stock-" + UUID.randomUUID();
        jdbc.update("INSERT INTO seller_users (seller_id, subject_id, role) VALUES (?, ?, 'OWNER')", sellerId, subjectId);
        UUID canonicalProductId = AllocationFixtures.seedCanonicalProduct(jdbc, "Stock Adjust Test Plant");
        listingId = AllocationFixtures.seedListing(jdbc, sellerId, canonicalProductId, "Stock Adjust Test Plant", "150.00", 5);
    }

    private int stockOf(UUID id) {
        return jdbc.queryForObject("SELECT quantity FROM inventory WHERE listing_id = ?", Integer.class, id);
    }

    @Test
    void restock_increasesQuantity_andRecordsRealActor() throws Exception {
        mockMvc.perform(post("/v1/listings/" + listingId + "/stock-adjust")
                .with(jwt().jwt(j -> j.subject(subjectId)))
                .contentType(APPLICATION_JSON).content("""
                    {"delta": 10, "reason": "RESTOCK"}
                    """))
            .andExpect(status().isOk());

        assertThat(stockOf(listingId)).isEqualTo(15);
        String actor = jdbc.queryForObject(
            "SELECT actor_id FROM inventory_adjustments WHERE listing_id = ? ORDER BY id DESC LIMIT 1",
            String.class, listingId);
        assertThat(actor).isEqualTo(subjectId);
    }

    @Test
    void mortalityCorrection_decreasesQuantity() throws Exception {
        mockMvc.perform(post("/v1/listings/" + listingId + "/stock-adjust")
                .with(jwt().jwt(j -> j.subject(subjectId)))
                .contentType(APPLICATION_JSON).content("""
                    {"delta": -2, "reason": "MORTALITY"}
                    """))
            .andExpect(status().isOk());

        assertThat(stockOf(listingId)).isEqualTo(3);
    }

    @Test
    void adjustment_requiresARealReason() throws Exception {
        mockMvc.perform(post("/v1/listings/" + listingId + "/stock-adjust")
                .with(jwt().jwt(j -> j.subject(subjectId)))
                .contentType(APPLICATION_JSON).content("""
                    {"delta": 5, "reason": "BECAUSE_I_FELT_LIKE_IT"}
                    """))
            .andExpect(status().isBadRequest());

        assertThat(stockOf(listingId)).isEqualTo(5); // untouched
    }

    @Test
    void adjustment_takingStockNegative_isRejected_notA500() throws Exception {
        mockMvc.perform(post("/v1/listings/" + listingId + "/stock-adjust")
                .with(jwt().jwt(j -> j.subject(subjectId)))
                .contentType(APPLICATION_JSON).content("""
                    {"delta": -100, "reason": "CORRECTION"}
                    """))
            .andExpect(status().isConflict());

        assertThat(stockOf(listingId)).isEqualTo(5); // untouched - the real DB CHECK rolled it back
    }

    /**
     * 404, not 403 - same real Row-Level Security behavior established
     * Day 45 (ListingController.get() hit the identical case): with real
     * RLS enforced, otherSubject's own tenant-scoped connection genuinely
     * cannot see sellerId's listing row at all, so findById() comes back
     * empty before this endpoint's own ownership check ever runs. Real
     * defense-in-depth for an RLS-misconfigured case, not dead code.
     */
    @Test
    void anotherSellersListing_cannotBeAdjusted() throws Exception {
        String otherSubject = "sub-other-" + UUID.randomUUID();
        UUID otherSeller = AllocationFixtures.seedApprovedSeller(jdbc, "Other Nursery " + UUID.randomUUID());
        jdbc.update("INSERT INTO seller_users (seller_id, subject_id, role) VALUES (?, ?, 'OWNER')", otherSeller, otherSubject);

        mockMvc.perform(post("/v1/listings/" + listingId + "/stock-adjust")
                .with(jwt().jwt(j -> j.subject(otherSubject)))
                .contentType(APPLICATION_JSON).content("""
                    {"delta": 10, "reason": "RESTOCK"}
                    """))
            .andExpect(status().isNotFound());
    }
}
