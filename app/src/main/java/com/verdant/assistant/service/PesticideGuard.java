package com.verdant.assistant.service;

import com.verdant.assistant.model.Diagnosis;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Never name a specific pesticide or chemical treatment (60.3's own
 * words) - a safety and liability question, and a wrong concentration
 * harms people, pets, or the plant. Generic guidance (isolate the
 * plant, wipe leaves, see a nursery) is fine and is what replaces a
 * caught violation.
 */
@Component
public class PesticideGuard {

    private static final Logger log = LoggerFactory.getLogger(PesticideGuard.class);

    private static final String GENERIC_PEST_GUIDANCE =
        "This looks like a pest issue. Isolate the plant from others, wipe leaves with a damp cloth, "
        + "and see a local nursery for a treatment appropriate to this specific plant and your household.";

    /** A representative, not exhaustive, list of common named pesticide/chemical active ingredients - real production would keep this current against an actual regulatory list rather than a fixed set. */
    private static final Pattern CHEMICAL_NAME_PATTERN = Pattern.compile(
        "(?i)\\b(imidacloprid|malathion|permethrin|neonicotinoid|chlorpyrifos|carbaryl|glyphosate|"
        + "acephate|dimethoate|cypermethrin|fipronil)\\b");

    private final MeterRegistry meters;

    public PesticideGuard(MeterRegistry meters) {
        this.meters = meters;
    }

    public void rejectPesticideNaming(Diagnosis diagnosis) {
        if (CHEMICAL_NAME_PATTERN.matcher(diagnosis.recommendation()).find()) {
            log.warn("Model named a chemical treatment, replacing with generic guidance");
            diagnosis.replaceRecommendationWith(GENERIC_PEST_GUIDANCE);
            meters.counter("doctor.pesticide.blocked").increment();
        }
    }
}
