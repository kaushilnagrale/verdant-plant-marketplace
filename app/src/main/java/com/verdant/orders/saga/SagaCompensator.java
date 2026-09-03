package com.verdant.orders.saga;

import org.springframework.stereotype.Component;

import java.util.List;

/** Reverse order, and every step idempotent - reverseSettlement posts a reversing ledger transaction rather than editing anything, exactly as Week 5 established. */
@Component
public class SagaCompensator {

    private final SagaCompletedStepRepository completedSteps;
    private final CancelShipmentStep cancelShipment;
    private final ReverseSettlementStep reverseSettlement;
    private final RefundPaymentStep refundPayment;
    private final ReleaseInventoryStep releaseInventory;

    public SagaCompensator(SagaCompletedStepRepository completedSteps, CancelShipmentStep cancelShipment,
                            ReverseSettlementStep reverseSettlement, RefundPaymentStep refundPayment,
                            ReleaseInventoryStep releaseInventory) {
        this.completedSteps = completedSteps;
        this.cancelShipment = cancelShipment;
        this.reverseSettlement = reverseSettlement;
        this.refundPayment = refundPayment;
        this.releaseInventory = releaseInventory;
    }

    /**
     * A step failure throws RetryableException rather than being handled
     * here directly - lets the orchestrator's existing retry/backoff/alert
     * logic handle both forward steps and compensation steps uniformly,
     * instead of duplicating that bookkeeping in two places.
     */
    public void compensate(OrderSaga saga) {
        List<CompensationStep> steps = compensationsFor(saga.getFurthestState());

        for (CompensationStep step : steps) {
            if (completedSteps.hasCompleted(saga.getOrderId(), step.name())) {
                continue; // resumable - a crash midway through compensation must not re-issue the refund
            }
            try {
                step.execute(saga);
                completedSteps.markCompleted(saga.getOrderId(), step.name());
            } catch (Exception e) {
                throw new RetryableException(
                    "Compensation step " + step.name() + " failed: " + e.getMessage(), e);
            }
        }

        saga.moveTo(SagaState.FAILED);
    }

    private List<CompensationStep> compensationsFor(SagaState furthest) {
        return switch (furthest) {
            case SHIPMENT_BOOKED, COMPLETED ->
                List.of(cancelShipment, reverseSettlement, refundPayment, releaseInventory);
            case SETTLEMENT_POSTED -> List.of(reverseSettlement, refundPayment, releaseInventory);
            case PAYMENT_CAPTURED -> List.of(refundPayment, releaseInventory);
            case STARTED, INVENTORY_RESERVED -> List.of(releaseInventory);
            default -> List.of();
        };
    }
}
