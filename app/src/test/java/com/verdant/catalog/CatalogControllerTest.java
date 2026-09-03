package com.verdant.catalog;

import com.verdant.common.testing.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** permitAll - no JWT needed, this is the public buyer-facing read path. */
@AutoConfigureMockMvc
class CatalogControllerTest extends IntegrationTestBase {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void browse_listsActiveListingsFromApprovedSellers_withoutAuthentication() throws Exception {
        UUID sellerId = jdbc.queryForObject(
            "INSERT INTO sellers (business_name, status) VALUES ('Catalog Test Nursery', 'APPROVED') RETURNING id",
            UUID.class);
        jdbc.update("""
            INSERT INTO listings (seller_id, raw_title, price_amount) VALUES (?, 'Catalog Test Plant', 149.00)
            """, sellerId);

        mockMvc.perform(get("/v1/catalog"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.title == 'Catalog Test Plant')]").exists());
    }

    @Test
    void browse_excludesListingsFromNonApprovedSellers() throws Exception {
        UUID sellerId = jdbc.queryForObject(
            "INSERT INTO sellers (business_name, status) VALUES ('Catalog Test Draft Nursery', 'DRAFT') RETURNING id",
            UUID.class);
        jdbc.update("""
            INSERT INTO listings (seller_id, raw_title, price_amount) VALUES (?, 'Draft Seller Plant', 149.00)
            """, sellerId);

        mockMvc.perform(get("/v1/catalog"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.title == 'Draft Seller Plant')]").doesNotExist());
    }

    @Test
    void detail_unknownListing_returns404() throws Exception {
        mockMvc.perform(get("/v1/catalog/" + UUID.randomUUID()))
            .andExpect(status().isNotFound());
    }

    @Test
    void detail_returnsFullListingInfo() throws Exception {
        UUID sellerId = jdbc.queryForObject(
            "INSERT INTO sellers (business_name, status) VALUES ('Catalog Detail Test Nursery', 'APPROVED') RETURNING id",
            UUID.class);
        UUID listingId = jdbc.queryForObject("""
            INSERT INTO listings (seller_id, raw_title, price_amount) VALUES (?, 'Detail Test Plant', 249.00) RETURNING id
            """, UUID.class, sellerId);

        mockMvc.perform(get("/v1/catalog/" + listingId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("Detail Test Plant"))
            .andExpect(jsonPath("$.sellerName").value("Catalog Detail Test Nursery"));
    }
}
