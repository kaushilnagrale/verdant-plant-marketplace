package com.verdant.checkout;

public record PaymentVerificationRequest(String razorpayOrderId, String razorpayPaymentId, String razorpaySignature) { }
