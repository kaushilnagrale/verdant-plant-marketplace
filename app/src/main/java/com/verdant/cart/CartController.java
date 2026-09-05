package com.verdant.cart;

import com.verdant.inventory.InsufficientStockException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
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

    // The consistency boundary (Week 9): a stale search hit reaching this
    // endpoint is normal, not a bug - 409, same status CheckoutService
    // already uses for the identical exceptions at checkout time, not 404
    // or 500. Same per-controller local-handler style as SellerController/
    // PayoutAdminController, not a global @ControllerAdvice.
    @ExceptionHandler(ListingNoLongerAvailableException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void handleListingNoLongerAvailable() { }

    @ExceptionHandler(InsufficientStockException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void handleInsufficientStock() { }

    @ExceptionHandler(SellerUnavailableException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void handleSellerUnavailable() { }
}
