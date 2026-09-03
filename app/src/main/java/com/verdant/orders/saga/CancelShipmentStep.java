package com.verdant.orders.saga;

import org.springframework.stereotype.Component;

@Component
public class CancelShipmentStep implements CompensationStep {

    private final LogisticsGateway logistics;

    public CancelShipmentStep(LogisticsGateway logistics) {
        this.logistics = logistics;
    }

    @Override
    public String name() {
        return "CANCEL_SHIPMENT";
    }

    @Override
    public void execute(OrderSaga saga) {
        logistics.cancel(saga.getOrderId());
    }
}
