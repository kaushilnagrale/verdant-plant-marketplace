package com.verdant.orders.allocation;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The buyer sees this split before paying, never after (see the cart preview endpoint). */
public record AllocationPlan(Map<UUID, List<Allocation>> bySeller, List<UnfulfillableLine> unfulfillable) {

    /** One entry per seller actually used - the buyer's shipment split. */
    public Collection<List<Allocation>> shipments() {
        return bySeller.values();
    }

    public List<Allocation> allShipments() {
        return bySeller.values().stream().flatMap(List::stream).toList();
    }

    public List<Allocation> allocated() {
        return allShipments();
    }

    /** Stable, comparable representation for the determinism test. */
    public List<Allocation> assignments() {
        return allShipments();
    }

    public UUID sellerFor(int index) {
        return allShipments().get(index).sellerId();
    }

    public boolean requiresBuyerConfirmation() {
        return !unfulfillable.isEmpty();
    }
}
