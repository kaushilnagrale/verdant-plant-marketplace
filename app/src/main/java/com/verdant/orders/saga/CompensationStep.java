package com.verdant.orders.saga;

/** One reversible piece of undoing a saga's forward progress. Each execute() must be safe to skip if already done (see SagaCompletedStepRepository). */
public interface CompensationStep {
    String name();
    void execute(OrderSaga saga);
}
