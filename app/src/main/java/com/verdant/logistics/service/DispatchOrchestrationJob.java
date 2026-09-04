package com.verdant.logistics.service;

import com.verdant.logistics.weather.DispatchGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Not in the runbook, and a real gap found while closing out Week 7:
 * DispatchGate and ShipmentBookingService were both built and thoroughly
 * tested (Day 39-41), but nothing in the live order flow ever actually
 * called either of them - the saga creates a shipments row (status
 * BOOKED, via LogisticsGateway) and stops there. This is the missing
 * link: picks up freshly-booked shipments with no AWB yet, evaluates the
 * weather gate first (which itself marks the shipment HELD and stops
 * here if conditions are unsafe - see DispatchGate), and only calls the
 * real courier booking if the gate says proceed. Runs far more often than
 * the 6-hourly forecast refresh - the cost of checking is cheap (a few DB
 * reads) even though the forecast itself is stale-tolerant, and a buyer
 * shouldn't wait hours for their shipment to even be considered.
 */
@Component
public class DispatchOrchestrationJob {

    private static final Logger log = LoggerFactory.getLogger(DispatchOrchestrationJob.class);

    private final JdbcTemplate jdbc;
    private final DispatchGate gate;
    private final ShipmentBookingService booking;

    public DispatchOrchestrationJob(JdbcTemplate jdbc, DispatchGate gate, ShipmentBookingService booking) {
        this.jdbc = jdbc;
        this.gate = gate;
        this.booking = booking;
    }

    @Scheduled(initialDelayString = "${verdant.dispatch.orchestration-initial-delay-ms:60000}",
               fixedDelayString = "${verdant.dispatch.orchestration-interval-ms:300000}")
    public void orchestrate() {
        for (UUID shipmentId : freshlyBookedShipments()) {
            try {
                if (gate.evaluate(shipmentId).isProceed()) {
                    booking.book(shipmentId);
                }
                // A hold is not a failure - DispatchGate already recorded it
                // and set the shipment to HELD; HoldReleaseJob owns what
                // happens to it from here.
            } catch (Exception e) {
                // One shipment's failure must never block every other
                // shipment in the same batch - same quarantine discipline
                // as ForecastRefreshJob.
                log.warn("Dispatch orchestration failed for shipment {}", shipmentId, e);
            }
        }
    }

    private List<UUID> freshlyBookedShipments() {
        return jdbc.queryForList(
            "SELECT id FROM shipments WHERE status = 'BOOKED' AND awb_number IS NULL", UUID.class);
    }
}
