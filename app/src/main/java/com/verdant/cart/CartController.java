package com.verdant.cart;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Buyer-facing, not seller-authenticated - there is no buyer identity model
 * in this project yet, and pricing a cart is a pre-checkout, read-only
 * operation (no state changes, no stock reservation happens here). See
 * SecurityConfig for the matching permitAll entry.
 */
@RestController
@RequestMapping("/v1/cart")
public class CartController {

    private final CartPricingService pricing;

    public CartController(CartPricingService pricing) {
        this.pricing = pricing;
    }

    @PostMapping("/quote")
    public CartQuote quote(@RequestBody List<CartLine> lines) {
        return pricing.priceCart(lines);
    }
}
