package com.verdant.claims.service;

import com.verdant.claims.domain.Fault;
import com.verdant.claims.domain.ItemFacts;
import com.verdant.claims.domain.ShipmentFacts;
import com.verdant.logistics.weather.DispatchHoldRepository;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Signals are heuristics, not verdicts - stored on the claim so a seller
 * disputing an attribution can see exactly what drove it. Confidence below
 * 0.70 routes to a human rather than auto-deciding.
 *
 * Two real gaps in the runbook's own version of this class, fixed here:
 * ZoneId was used but never imported, and `holds` was referenced with no
 * field or constructor to provide it - wired to the real
 * DispatchHoldRepository (Week 7), whose hadManualOverride() this needed
 * added.
 */
@Service
public class FaultAttributor {

    private final DispatchHoldRepository holds;

    public FaultAttributor(DispatchHoldRepository holds) {
        this.holds = holds;
    }

    public Attribution attribute(ShipmentFacts shipment, ItemFacts item) {
        Map<String, Object> signals = new LinkedHashMap<>();

        Duration dispatchDelay = Duration.between(
            shipment.plannedDispatchDate().atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant(),
            shipment.dispatchedAt());
        signals.put("dispatchDelayHours", dispatchDelay.toHours());

        long actualTransitDays = Duration.between(shipment.dispatchedAt(), shipment.deliveredAt()).toDays();
        signals.put("actualTransitDays", actualTransitDays);
        signals.put("estimatedTransitDays", shipment.transitDaysEstimate());

        boolean weatherOverridden = holds.hadManualOverride(shipment.shipmentId());
        signals.put("weatherOverridden", weatherOverridden);

        // Platform overrode its own gate: the platform owns the outcome
        if (weatherOverridden) {
            return new Attribution(Fault.PLATFORM, 0.90, signals);
        }

        // Courier held it far longer than estimated
        if (actualTransitDays > shipment.transitDaysEstimate() + 2) {
            return new Attribution(Fault.COURIER, 0.80, signals);
        }

        // Seller sat on it before handing over
        if (dispatchDelay.toHours() > item.sellerHandlingTimeHours() + 24) {
            return new Attribution(Fault.SELLER, 0.75, signals);
        }

        // Delivered on time, plant still dead: most likely packing
        if (actualTransitDays <= shipment.transitDaysEstimate()) {
            return new Attribution(Fault.SELLER, 0.55, signals);
        }

        return new Attribution(Fault.UNDETERMINED, 0.0, signals);
    }

    public record Attribution(Fault fault, double confidence, Map<String, Object> signals) {
        public boolean needsHumanReview() {
            return confidence < 0.70;
        }

        /** What actually gets stored in claims.fault_signals (a real jsonb column). */
        public String signalsJson() {
            return new JSONObject(signals).toString();
        }
    }
}
