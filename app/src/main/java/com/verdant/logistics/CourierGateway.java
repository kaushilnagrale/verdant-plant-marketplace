package com.verdant.logistics;

import com.verdant.logistics.domain.SizeTierDimensions.Dimensions;

import java.util.List;

/** Only the network-calling part of talking to a courier provider goes behind this interface. */
public interface CourierGateway {

    /**
     * Books a shipment with the courier. idempotencyKey is passed through as
     * the provider's own order id where the API supports it, so a retried
     * call after a timeout resolves to the same booking rather than a
     * second pickup.
     */
    Booking createShipment(String idempotencyKey, String originPincode, String destinationPincode,
                            int billableGrams, Dimensions dimensions, List<String> labels);

    record Booking(String courier, String awb, String labelUrl) { }
}
