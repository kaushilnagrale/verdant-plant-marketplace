package com.verdant.assistant.service;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Deliberately a real, simple, deterministic heuristic - not a model
 * call - and checked BEFORE retrieval even runs (59.1/59.3's own
 * emphasis: "out of scope questions never reach retrieval"). A real ML
 * classifier would need real labelled data and a real model this
 * project doesn't have either; a plain keyword/phrase check is
 * something a reviewer can read and verify completely, which matters
 * more here than marginal accuracy - the concierge only needs to
 * recognize "this isn't about choosing a plant" confidently enough to
 * refuse, not classify intent generally.
 */
@Component
public class ScopeClassifier {

    private static final List<String> OUT_OF_SCOPE_PHRASES = List.of(
        "order status", "where is my order", "where is my refund", "refund",
        "discount code", "coupon", "promo code", "ignore your instructions",
        "ignore previous instructions", "cancel my order", "track my order",
        "complaint", "password", "credit card", "payment failed"
    );

    public boolean isOutOfScope(String query) {
        String lower = query.toLowerCase();
        return OUT_OF_SCOPE_PHRASES.stream().anyMatch(lower::contains);
    }
}
