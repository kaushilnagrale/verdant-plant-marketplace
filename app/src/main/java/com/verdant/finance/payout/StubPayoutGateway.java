package com.verdant.finance.payout;

import com.verdant.common.domain.Money;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The only implementation, in every profile - see PayoutGateway's own doc
 * comment for why. Records what it was asked to do (real, inspectable
 * state - the same honesty standard as Alerting/NotificationService) so
 * tests and a real ops person reading logs can both see exactly what a
 * real transfer call would have carried.
 */
@Component
public class StubPayoutGateway implements PayoutGateway {

    public record Transfer(String idempotencyKey, UUID sellerId, Money amount, String providerRef) { }

    private final List<Transfer> transfers = new CopyOnWriteArrayList<>();
    private final AtomicBoolean failNext = new AtomicBoolean(false);

    @Override
    public String transfer(String idempotencyKey, UUID sellerId, Money amount) {
        if (failNext.compareAndSet(true, false)) {
            throw new RuntimeException("Simulated payout provider failure");
        }
        String providerRef = "payout_stub_" + UUID.randomUUID();
        transfers.add(new Transfer(idempotencyKey, sellerId, amount, providerRef));
        return providerRef;
    }

    public void failNextTransfer() {
        failNext.set(true);
    }

    public List<Transfer> transfers() {
        return List.copyOf(transfers);
    }

    public void reset() {
        transfers.clear();
        failNext.set(false);
    }
}
