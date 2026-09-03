package com.verdant.logistics.web;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

/**
 * Shiprocket reports tracking updates by AWB, not by any id of ours - the
 * webhook handler resolves our internal shipment via awb_number. Field
 * names (awb, current_status, current_status_id used as the dedup key,
 * location, remark, updated_at) match Shiprocket's commonly documented
 * webhook payload shape - NOT yet live-verified against a real webhook
 * delivery (pending real credentials, same as ShiprocketGateway).
 */
public record TrackingEvent(String awb, String providerEventId, String status,
                             String location, String remark, Instant occurredAt) {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    public static TrackingEvent parse(byte[] raw) {
        JsonNode root = MAPPER.readTree(raw);
        String awb = root.path("awb").asString(null);
        String eventId = root.path("current_status_id").asString(null);
        String status = root.path("current_status").asString("");
        String location = root.path("location").asString(null);
        String remark = root.path("remark").asString(null);
        String updatedAt = root.path("updated_at").asString(null);

        Instant occurredAt = updatedAt != null ? Instant.parse(updatedAt) : Instant.now();
        return new TrackingEvent(awb, eventId, status, location, remark, occurredAt);
    }
}
