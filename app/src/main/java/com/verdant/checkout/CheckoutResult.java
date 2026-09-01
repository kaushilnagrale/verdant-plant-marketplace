package com.verdant.checkout;

import com.verdant.common.domain.Money;

import java.util.UUID;

/** What the buyer's client needs to open Razorpay's Checkout widget. razorpayKeyId is public, safe to expose. */
public record CheckoutResult(UUID orderId, String razorpayOrderId, String razorpayKeyId, Money totalAmount) { }
