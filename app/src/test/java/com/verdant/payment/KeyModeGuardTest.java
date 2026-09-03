package com.verdant.payment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * A pure unit test of the guard's logic, not a full prod-profile Spring
 * context (application-prod.yml requires many env vars - DB_URL,
 * REDIS_HOST, KAFKA_BOOTSTRAP_SERVERS, OIDC_ISSUER_URI - that don't exist
 * in a test run, so a full context would fail for unrelated reasons before
 * ever reaching this guard). The constructor's logic is what matters here.
 */
class KeyModeGuardTest {

    @Test
    void testKeyUnderProdProfile_isRejected() {
        RazorpayProperties testKeyProps = new RazorpayProperties("rzp_test_abc123", "secret", "webhook-secret");

        assertThatThrownBy(() -> new KeyModeGuard(testKeyProps))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("TEST key");
    }

    @Test
    void liveKey_isAccepted() {
        RazorpayProperties liveKeyProps = new RazorpayProperties("rzp_live_abc123", "secret", "webhook-secret");

        assertThatCode(() -> new KeyModeGuard(liveKeyProps)).doesNotThrowAnyException();
    }
}
