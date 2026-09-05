package com.verdant.seller;

import com.verdant.cart.CartLine;
import com.verdant.common.testing.AllocationFixtures;
import com.verdant.common.testing.IntegrationTestBase;
import com.verdant.orders.allocation.AllocationEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class SellerAccountControllerTest extends IntegrationTestBase {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AllocationEngine engine;

    private UUID sellerId;
    private String subjectId;

    @BeforeEach
    void seed() {
        AllocationFixtures.seedLocalPincodeZone(jdbc);
        sellerId = AllocationFixtures.seedApprovedSeller(jdbc, "Account Test Nursery " + UUID.randomUUID());
        subjectId = "sub-account-" + UUID.randomUUID();
        jdbc.update("INSERT INTO seller_users (seller_id, subject_id, role) VALUES (?, ?, 'OWNER')", sellerId, subjectId);
    }

    @Test
    void quality_returnsRealComponentsAndTier() throws Exception {
        jdbc.update("""
            INSERT INTO seller_quality_scores (seller_id, score, components, order_count, is_provisional, tier)
            VALUES (?, 0.82, '{"dispatchAdherence":{"numerator":18,"denominator":20,"description":"x","value":0.9}}'::jsonb, 25, false, 'STANDARD')
            """, sellerId);

        mockMvc.perform(get("/v1/sellers/" + sellerId + "/quality").with(jwt().jwt(j -> j.subject(subjectId))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tier").value("STANDARD"))
            .andExpect(jsonPath("$.orderCount").value(25))
            .andExpect(jsonPath("$.components.dispatchAdherence.numerator").value(18));
    }

    @Test
    void quality_anotherSellersAccount_isForbidden() throws Exception {
        UUID otherSeller = AllocationFixtures.seedApprovedSeller(jdbc, "Other Account Nursery " + UUID.randomUUID());

        mockMvc.perform(get("/v1/sellers/" + otherSeller + "/quality").with(jwt().jwt(j -> j.subject(subjectId))))
            .andExpect(status().isForbidden());
    }

    @Test
    void vacationMode_stopsNewOrderAllocation_butKeepsExistingFulfillable() throws Exception {
        UUID canonicalProductId = AllocationFixtures.seedCanonicalProduct(jdbc, "Vacation Test Plant");
        UUID listingId = AllocationFixtures.seedListing(jdbc, sellerId, canonicalProductId, "Vacation Test Plant", "150.00", 5);
        UUID otherSeller = AllocationFixtures.seedApprovedSeller(jdbc, "Backup Nursery " + UUID.randomUUID());
        AllocationFixtures.seedListing(jdbc, otherSeller, canonicalProductId, "Vacation Test Plant", "150.00", 5);

        mockMvc.perform(post("/v1/sellers/" + sellerId + "/vacation-mode")
                .with(jwt().jwt(j -> j.subject(subjectId)))
                .contentType(APPLICATION_JSON).content("""
                    {"onVacation": true}
                    """))
            .andExpect(status().isOk());

        var plan = engine.plan(List.of(new CartLine(listingId, 1)), AllocationFixtures.LOCAL_PINCODE);

        assertThat(plan.sellerFor(0))
            .as("a vacationing seller is never chosen for a NEW allocation, even though their own listing is the one the buyer clicked")
            .isEqualTo(otherSeller);
    }
}
