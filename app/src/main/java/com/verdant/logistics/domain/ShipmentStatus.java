package com.verdant.logistics.domain;

/**
 * Order matches the CHECK constraint in V25 and matters for
 * ShipmentStatusProjector: tracking events genuinely arrive out of order
 * and duplicated, and "the furthest status ever seen" (max ordinal among
 * the whole event log) is what the projector reports - not "whatever
 * event just arrived", which would flip a DELIVERED shipment back to
 * IN_TRANSIT if a late/reordered courier webhook shows up. FAILED/RTO/
 * CANCELLED are terminal exception states and aren't expected to compete
 * with the happy-path progression for "furthest" in practice.
 */
public enum ShipmentStatus {
    PENDING,
    HELD,
    READY,
    BOOKED,
    PACKED,
    DISPATCHED,
    PICKED_UP,
    IN_TRANSIT,
    OUT_FOR_DELIVERY,
    DELIVERED,
    FAILED,
    RTO,
    CANCELLED
}
