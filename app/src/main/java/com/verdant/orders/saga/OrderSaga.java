package com.verdant.orders.saga;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "order_sagas")
public class OrderSaga {

    @Id
    @Column(name = "order_id")
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SagaState state;

    @Enumerated(EnumType.STRING)
    @Column(name = "furthest_state", nullable = false)
    private SagaState furthestState;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    protected OrderSaga() { } // required by JPA

    /**
     * Not started at STARTED - CheckoutService already reserves inventory
     * synchronously before the saga row is even created, so the saga's real
     * job begins one step further along than the runbook's model assumes.
     */
    public OrderSaga(UUID orderId, SagaState initialState) {
        this.orderId = orderId;
        this.state = initialState;
        this.furthestState = initialState;
    }

    public void moveTo(SagaState next) {
        this.state = next;
        if (next.isForwardProgress() && next.ordinal() > furthestState.ordinal()) {
            this.furthestState = next;
        }
        this.updatedAt = Instant.now();
    }

    public void scheduleRetry(Instant nextAttempt, String error) {
        this.attempts++;
        this.nextAttemptAt = nextAttempt;
        this.lastError = error;
        this.updatedAt = Instant.now();
    }

    public void resetAttempts() {
        this.attempts = 0;
        this.lastError = null;
        this.updatedAt = Instant.now();
    }

    public void recordError(String error) {
        this.lastError = error;
        this.updatedAt = Instant.now();
    }

    public UUID getOrderId() { return orderId; }
    public SagaState getState() { return state; }
    public SagaState getFurthestState() { return furthestState; }
    public int getAttempts() { return attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public String getLastError() { return lastError; }
    public Instant getUpdatedAt() { return updatedAt; }
}
