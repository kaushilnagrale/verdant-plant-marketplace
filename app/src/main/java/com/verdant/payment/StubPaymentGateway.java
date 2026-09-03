package com.verdant.payment;

import com.verdant.common.domain.Money;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Profile("test")
public class StubPaymentGateway implements PaymentGateway {

    private final AtomicInteger refundCallCount = new AtomicInteger(0);
    private final AtomicInteger refundFailuresRemaining = new AtomicInteger(0);

    @Override
    public String createOrder(Money amount, String receiptId) {
        return "order_stub_" + UUID.randomUUID();
    }

    @Override
    public String refund(String paymentId, Money amount) {
        refundCallCount.incrementAndGet();
        if (refundFailuresRemaining.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw new RuntimeException("Simulated refund failure");
        }
        return "rfnd_stub_" + UUID.randomUUID();
    }

    /** Makes the next n refund() calls throw, then succeed again - saga compensation-retry tests. */
    public void failNextRefunds(int n) {
        refundFailuresRemaining.set(n);
    }

    public int refundCallCount() {
        return refundCallCount.get();
    }

    public void reset() {
        refundCallCount.set(0);
        refundFailuresRemaining.set(0);
    }
}
