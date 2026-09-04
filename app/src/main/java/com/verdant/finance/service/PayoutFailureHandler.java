package com.verdant.finance.service;

import com.verdant.common.alerting.Alerting;
import com.verdant.common.notification.NotificationService;
import com.verdant.finance.domain.PayoutCycle;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** Reversal, not deletion - the failed payout stays in history and the balance is restored by a new transaction. */
@Service
public class PayoutFailureHandler {

    private final PayoutCycleRepository cycles;
    private final LedgerService ledger;
    private final NotificationService notifications;
    private final Alerting alerting;

    public PayoutFailureHandler(PayoutCycleRepository cycles, LedgerService ledger,
                                 NotificationService notifications, Alerting alerting) {
        this.cycles = cycles;
        this.ledger = ledger;
        this.notifications = notifications;
        this.alerting = alerting;
    }

    public void handleFailure(UUID cycleId, String reason) {
        // Guard first: an already-FAILED cycle must not be reversed twice
        // (a second reversal of the same posting would double-credit the
        // seller). markFailed()'s own WHERE status <> 'FAILED' is what
        // actually enforces this atomically.
        int marked = cycles.markFailed(cycleId, reason);
        if (marked == 0) {
            return; // already handled
        }

        PayoutCycle cycle = cycles.findById(cycleId);
        ledger.reverse(cycle.ledgerTxnId(), "payout-reversal:" + cycleId, "Payout failed: " + reason);

        notifications.notifySeller(cycle.sellerId(), "PAYOUT_FAILED", reason);
        alerting.raise("Payout failed for seller " + cycle.sellerId() + ": " + reason);
    }
}
