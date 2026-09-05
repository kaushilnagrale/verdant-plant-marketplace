package com.verdant.assistant.service;

import com.verdant.assistant.domain.SymptomDetection;
import org.springframework.stereotype.Component;

/**
 * No real vision-model account exists for this project either - same
 * honest treatment as StubPlantAssistantModel. Recognizes a handful of
 * real, descriptive fake image keys a test constructs directly (e.g.
 * "yellow-leaves.jpg") and returns a real, plausible SymptomDetection
 * for each, rather than anything actually derived from image bytes -
 * there are no real bytes to derive anything from. A blurry/ambiguous
 * key deliberately returns low confidence, exercising the real
 * "request a better photo" guard (60.2) exactly like a real vision
 * model failing to see anything useful would.
 */
@Component
public class StubVisionService implements VisionService {

    @Override
    public SymptomDetection detectSymptoms(String imageKey) {
        String key = imageKey.toLowerCase();
        if (key.contains("blurry") || key.contains("ambiguous")) {
            return SymptomDetection.of("unclear image, no distinguishable symptoms", 0.20);
        }
        if (key.contains("pest") || key.contains("mite") || key.contains("mealybug")) {
            return SymptomDetection.of("fine webbing and speckled dots on leaf surface", 0.88);
        }
        if (key.contains("yellow")) {
            return SymptomDetection.of("yellowing of lower, older leaves", 0.90);
        }
        if (key.contains("brown") || key.contains("crispy")) {
            return SymptomDetection.of("brown, crispy leaf tips and edges", 0.85);
        }
        if (key.contains("droop") || key.contains("wilt")) {
            return SymptomDetection.of("drooping, wilted leaves", 0.82);
        }
        // A real photo of something not covered above - a plausible generic
        // observation rather than a confident guess at a specific symptom.
        return SymptomDetection.of("some leaf discoloration, not clearly one known pattern", 0.60);
    }
}
