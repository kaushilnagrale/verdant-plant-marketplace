package com.verdant.checkout;

import com.verdant.buyer.service.BuyerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/** Authenticated (buyer JWT) - relies on SecurityConfig's default anyRequest().authenticated(), same as SellerOrderController. */
@RestController
@RequestMapping("/v1/checkout")
public class CheckoutController {

    private final CheckoutService checkout;
    private final PaymentVerificationService verification;
    private final BuyerRepository buyers;

    public CheckoutController(CheckoutService checkout, PaymentVerificationService verification, BuyerRepository buyers) {
        this.checkout = checkout;
        this.verification = verification;
        this.buyers = buyers;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CheckoutResult checkout(@AuthenticationPrincipal Jwt jwt, @RequestBody CheckoutRequest request) {
        return checkout.checkout(currentBuyerId(jwt), request);
    }

    @PostMapping("/{orderId}/verify")
    public void verify(@PathVariable UUID orderId, @RequestBody PaymentVerificationRequest request) {
        verification.verifyPayment(orderId, request);
    }

    private UUID currentBuyerId(Jwt jwt) {
        return buyers.findBySubjectId(jwt.getSubject())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "No buyer account mapped to this login"))
            .id();
    }
}
