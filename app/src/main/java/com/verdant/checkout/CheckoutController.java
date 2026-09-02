package com.verdant.checkout;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/checkout")
public class CheckoutController {

    private final CheckoutService checkout;
    private final PaymentVerificationService verification;

    public CheckoutController(CheckoutService checkout, PaymentVerificationService verification) {
        this.checkout = checkout;
        this.verification = verification;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CheckoutResult checkout(@RequestBody CheckoutRequest request) {
        return checkout.checkout(request);
    }

    @PostMapping("/{orderId}/verify")
    public void verify(@PathVariable UUID orderId, @RequestBody PaymentVerificationRequest request) {
        verification.verifyPayment(orderId, request);
    }
}
