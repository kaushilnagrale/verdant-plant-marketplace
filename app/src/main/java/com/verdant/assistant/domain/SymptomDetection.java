package com.verdant.assistant.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Vision extracts SYMPTOMS ONLY, never a diagnosis (60.2's own emphasis) - "yellowing lower leaves" is an observation; "overwatered" is a conclusion that belongs to the retrieved guidance, not to this step. confidence is BigDecimal - see Scored's own comment for why every domain numeric field in this project is. */
public record SymptomDetection(String describe, BigDecimal confidence) {

    public static SymptomDetection of(String describe, double confidence) {
        return new SymptomDetection(describe, BigDecimal.valueOf(confidence).setScale(4, RoundingMode.HALF_UP));
    }
}
