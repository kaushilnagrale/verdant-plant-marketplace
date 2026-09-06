package com.verdant.buyer.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One per (order, seller) - a multi-seller order ships as separate real parcels, each tracked independently, exactly as AllocationEngine/ShipmentBookingService already model it. */
public record ShipmentTracking(UUID shipmentId, String sellerBusinessName, String status, String courier,
                                String awbNumber, Integer transitDays, Instant dispatchedAt, Instant deliveredAt,
                                List<ShipmentTrackingEvent> events) { }
