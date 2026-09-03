package com.verdant.orders.saga;

public enum SagaState {
    STARTED,
    INVENTORY_RESERVED,
    PAYMENT_CAPTURED,
    SETTLEMENT_POSTED,
    SHIPMENT_BOOKED,
    COMPLETED,
    COMPENSATING,
    FAILED;

    /** Everything except the two "something went wrong" states counts as forward progress for furthest_state tracking. */
    public boolean isForwardProgress() {
        return this != COMPENSATING && this != FAILED;
    }
}
