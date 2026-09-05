package com.verdant.assistant.domain;

import java.util.List;
import java.util.UUID;

public record DoctorResponse(Outcome outcome, String message, String species, String recommendation,
                              List<UUID> citations) {

    public static DoctorResponse needBetterPhoto(String message) {
        return new DoctorResponse(Outcome.REFUSED_NO_RETRIEVAL, message, null, null, List.of());
    }

    public static DoctorResponse escalate(String message) {
        return new DoctorResponse(Outcome.ESCALATED, message, null, null, List.of());
    }

    public static DoctorResponse answered(String species, String recommendation, List<UUID> citations) {
        return new DoctorResponse(Outcome.ANSWERED, recommendation, species, recommendation, citations);
    }
}
