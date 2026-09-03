package com.verdant.orders.saga;

import com.verdant.inventory.ReservationService;
import org.springframework.stereotype.Component;

@Component
public class ReleaseInventoryStep implements CompensationStep {

    private final ReservationService reservations;

    public ReleaseInventoryStep(ReservationService reservations) {
        this.reservations = reservations;
    }

    @Override
    public String name() {
        return "RELEASE_INVENTORY";
    }

    @Override
    public void execute(OrderSaga saga) {
        // Compensation always means "fully undo this order," regardless of
        // whether payment ever succeeded - so this must ignore the
        // fulfilled_at guard uniformly, not just when a refund is known to
        // be happening. releaseByOrder() (respects fulfilled_at) is for the
        // normal expiry/cancellation path and must never be used here - a
        // reservation whose payment succeeded would otherwise never be
        // released even though its payment is being refunded by this same
        // compensation run.
        reservations.releaseByOrderForRefund(saga.getOrderId()); // idempotent on its own (Day 21)
    }
}
