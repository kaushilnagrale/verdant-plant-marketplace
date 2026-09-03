package com.verdant.payment;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Refuses to let the application start under the "prod" profile if the
 * configured Razorpay key is a TEST key. Real Razorpay live keys start
 * with rzp_live_, test keys with rzp_test_ - a test key silently accepted
 * as "real" in production would mean actual customers' payments never
 * genuinely capture, discovered only when the money never arrives.
 *
 * Throwing from a bean constructor fails Spring's context refresh, which
 * fails application startup entirely - the app never opens a port to
 * accept traffic with a misconfigured key.
 */
@Component
@Profile("prod")
public class KeyModeGuard {

    public KeyModeGuard(RazorpayProperties props) {
        if (props.keyId() != null && props.keyId().startsWith("rzp_test_")) {
            throw new IllegalStateException(
                "Refusing to start under the prod profile with a Razorpay TEST key (" + props.keyId()
                    + "). Live keys start with rzp_live_.");
        }
    }
}
