package com.verdant.orders.saga;

import java.util.UUID;

/**
 * No real courier integration exists yet (Week 7 adds Shiprocket) -
 * StubLogisticsGateway is the only implementation for now, but it does
 * persist real shipment rows (Day 34) - only the actual courier call is
 * stubbed. Per-seller, not per-order: an order can span multiple sellers
 * (order_items), and each needs to independently mark their own portion
 * packed/dispatched.
 */
public interface LogisticsGateway {
    /** Books a shipment for one seller's portion of an order and returns the gateway-side shipment reference. */
    String book(UUID orderId, UUID sellerId);

    /** Cancels a previously booked shipment (saga compensation). */
    void cancel(UUID orderId, UUID sellerId);
}
