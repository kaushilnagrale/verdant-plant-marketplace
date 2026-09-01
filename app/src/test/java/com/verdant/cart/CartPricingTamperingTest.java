package com.verdant.cart;

import com.verdant.common.testing.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.closeTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves price tampering has no effect: a client cannot smuggle its own
 * price into a cart quote, because CartLine has no field for one to bind
 * to. The total returned must always come from the listing's real price.
 */
@AutoConfigureMockMvc
class CartPricingTamperingTest extends IntegrationTestBase {

    private static final String REAL_UNIT_PRICE = "499.00";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void quote_ignoresClientSuppliedPrice_usesRealListingPrice() throws Exception {
        UUID sellerId = jdbc.queryForObject(
            "INSERT INTO sellers (business_name, status) VALUES (?, 'APPROVED') RETURNING id",
            UUID.class, "Tampering Test Nursery");
        UUID listingId = jdbc.queryForObject("""
            INSERT INTO listings (seller_id, raw_title, price_amount)
            VALUES (?, 'Rare Monstera', %s) RETURNING id
            """.formatted(REAL_UNIT_PRICE), UUID.class, sellerId);

        // A tampered client tries to smuggle a fake price into the request.
        // CartLine has no "price" field, so Jackson has nowhere to bind it -
        // it is silently ignored, not read.
        String tamperedRequest = """
            [{"listingId":"%s","quantity":2,"price":"0.01","priceOverride":"0.01"}]
            """.formatted(listingId);

        mockMvc.perform(post("/v1/cart/quote")
                .contentType(MediaType.APPLICATION_JSON)
                .content(tamperedRequest))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lines[0].unitPrice.amount").value(closeTo(499.00, 0.001)))
            .andExpect(jsonPath("$.total.amount").value(closeTo(998.00, 0.001))); // 499.00 x 2, NOT 0.02
    }

    @Test
    void quote_unknownListing_returns404() throws Exception {
        String request = """
            [{"listingId":"%s","quantity":1}]
            """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/v1/cart/quote")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
            .andExpect(status().isNotFound());
    }
}
