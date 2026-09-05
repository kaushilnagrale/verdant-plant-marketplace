package com.verdant.payment;

import com.verdant.common.domain.Money;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Profile("test")
public class StubPaymentGateway implements PaymentGateway {

    private final AtomicInteger refundCallCount = new AtomicInteger(0);
    private final AtomicInteger refundFailuresRemaining = new AtomicInteger(0);
    private final Map<LocalDate, List<ProviderPayment>> capturedByDate = new ConcurrentHashMap<>();

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

    @Override
    public List<ProviderPayment> paymentsCapturedOn(LocalDate date) {
        return capturedByDate.getOrDefault(date, List.of());
    }

    /** Reconciliation tests (Day 46): controls exactly what "Razorpay" reports for a given day. */
    public void seedCapturedPayments(LocalDate date, List<ProviderPayment> payments) {
        capturedByDate.put(date, payments);
    }

    public void reset() {
        refundCallCount.set(0);
        refundFailuresRemaining.set(0);
        capturedByDate.clear();
    }
}
