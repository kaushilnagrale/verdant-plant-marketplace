package com.verdant.orders.saga;

import java.util.UUID;

/** No real logistics integration exists yet (Week 7 adds Shiprocket) - StubLogisticsGateway is the only implementation for now. */
public interface LogisticsGateway {
    /** Books a shipment for an order and returns the gateway-side shipment reference. */
    String book(UUID orderId);

    /** Cancels a previously booked shipment (saga compensation). */
    void cancel(UUID orderId);
}
