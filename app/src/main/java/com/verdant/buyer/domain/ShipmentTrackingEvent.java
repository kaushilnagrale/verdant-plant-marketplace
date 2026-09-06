package com.verdant.buyer.domain;

import java.time.Instant;

/** Real courier tracking events (TrackingWebhookController, Week 7) - never fabricated, absent for a shipment Shiprocket hasn't sent an update on yet. */
public record ShipmentTrackingEvent(String status, String location, String remark, Instant occurredAt) { }
