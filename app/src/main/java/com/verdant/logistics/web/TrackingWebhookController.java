package com.verdant.logistics.web;

import com.verdant.logistics.service.ShipmentEventRepository;
import com.verdant.logistics.service.ShipmentStatusProjector;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.UUID;

/**
 * Shiprocket's own servers call this directly - no buyer JWT involved,
 * same shape as RazorpayWebhookController (Week 4): reads the raw body
 * itself via getInputStream() rather than binding @RequestBody, so there's
 * no charset decode/re-encode risk before the body reaches TrackingEvent.parse().
 * Permitted via the existing /v1/webhooks/** rule in SecurityConfig.
 */
@RestController
@RequestMapping("/v1/webhooks")
public class TrackingWebhookController {

    private final ShiprocketWebhookVerifier verifier;
    private final ShipmentEventRepository shipmentEvents;
    private final ShipmentStatusProjector statusProjector;

    public TrackingWebhookController(ShiprocketWebhookVerifier verifier, ShipmentEventRepository shipmentEvents,
                                      ShipmentStatusProjector statusProjector) {
        this.verifier = verifier;
        this.shipmentEvents = shipmentEvents;
        this.statusProjector = statusProjector;
    }

    @PostMapping("/shiprocket")
    public ResponseEntity<Void> handle(HttpServletRequest request,
                                        @RequestHeader("X-Api-Key") String apiKey) throws IOException {
        if (!verifier.isValid(apiKey)) {
            return ResponseEntity.status(401).build();
        }

        byte[] raw = request.getInputStream().readAllBytes();
        TrackingEvent event = TrackingEvent.parse(raw);

        UUID shipmentId = shipmentEvents.shipmentIdForAwb(event.awb()).orElse(null);
        if (shipmentId == null) {
            // Unknown AWB - accepted anyway so Shiprocket doesn't retry
            // forever, same reasoning as RazorpayWebhookController's
            // unknown-order case.
            return ResponseEntity.ok().build();
        }

        try {
            shipmentEvents.insert(shipmentId, event);
        } catch (DataIntegrityViolationException duplicate) {
            return ResponseEntity.ok().build(); // already recorded - not an error, just a repeat delivery
        }

        statusProjector.project(shipmentId);
        return ResponseEntity.ok().build();
    }
}
