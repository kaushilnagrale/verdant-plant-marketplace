package com.verdant.catalog;

import com.verdant.common.testing.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ListingAuthorizationTest extends IntegrationTestBase {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void sellerStaff_queryingAnotherSellersInventory_returns404() throws Exception {
        // Was 403 before real RLS enforcement (see TenantAwareDataSource):
        // ListingController.get() did findById() then a manual ownership
        // check, and findById() - running as a superuser - could always
        // see seller B's row, so the ownership check was reachable and
        // fired 403. Now a connection scoped to seller A's tenant genuinely
        // cannot see seller B's row at the database level; findById()
        // returns empty and the controller's own "not found" branch fires
        // first. 404 is the correct outcome here, not a regression to
        // paper over - the same trade-off GitHub and Stripe make
        // deliberately for private/cross-tenant resources, so that probing
        // an ID never reveals whether it exists versus is merely
        // forbidden.
        UUID sellerA = insertSeller("Nursery A");
        UUID sellerB = insertSeller("Nursery B");
        String subA = "sub-a-" + sellerA;
        String subB = "sub-b-" + sellerB;
        insertUser(sellerA, subA);
        insertUser(sellerB, subB);
        UUID listingB = insertListing(sellerB, "Fern");

        mockMvc.perform(get("/v1/listings/" + listingB)
                .with(jwt().jwt(j -> j.subject(subA))))
            .andExpect(status().isNotFound());
    }

    private UUID insertSeller(String name) {
        return jdbc.queryForObject(
            "INSERT INTO sellers (business_name, status) VALUES (?, 'APPROVED') RETURNING id",
            UUID.class, name);
    }

    private void insertUser(UUID sellerId, String subject) {
        jdbc.update("INSERT INTO seller_users (seller_id, subject_id, role) VALUES (?, ?, 'OWNER')",
            sellerId, subject);
    }

    private UUID insertListing(UUID sellerId, String title) {
        return jdbc.queryForObject(
            "INSERT INTO listings (seller_id, raw_title, price_amount) VALUES (?, ?, 100.00) RETURNING id",
            UUID.class, sellerId, title);
    }
}
