package com.verdant.assistant.model;

import java.util.List;
import java.util.UUID;

public final class Diagnosis {
    private final List<UUID> citations;
    private String recommendation;

    public Diagnosis(List<UUID> citations, String recommendation) {
        this.citations = citations;
        this.recommendation = recommendation;
    }

    public List<UUID> citations() {
        return citations;
    }

    public String recommendation() {
        return recommendation;
    }

    /** PesticideGuard's own real mutation point - replacing the model's own words after the fact, not asking it to try again, since there is nothing to validate a second attempt against that a regex can't already check directly. */
    public void replaceRecommendationWith(String generic) {
        this.recommendation = generic;
    }
}
