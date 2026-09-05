package com.verdant.assistant.model;

import com.verdant.assistant.domain.AvailableListing;
import com.verdant.assistant.domain.Scored;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * No @Profile restriction - active in every profile including prod,
 * same treatment as StubPayoutGateway/StubWhatsAppGateway: there is no
 * real chat-completion account for this project to fall back to either.
 *
 * Default behavior is a real, well-behaved stand-in (picks from what it
 * was actually offered, cites what was actually retrieved) so the
 * concierge/doctor's own happy-path tests exercise real validation
 * logic against a real-shaped response, not just against a deliberately
 * broken one. Test-configurable overrides simulate a MISBEHAVING model
 * (the runbook's own chatStub.willReturnListingIds(...) etc.) so the
 * real Java validation code - the actual point of this whole feature,
 * per 61's own framing ("anyone can call an LLM, the interesting part
 * is the guardrails") - gets to prove it actually catches a bad output,
 * not just that a good one passes.
 */
@Component
public class StubPlantAssistantModel implements PlantAssistantModel {

    private final AtomicInteger callCount = new AtomicInteger(0);
    private List<UUID> forcedListingIds;
    private boolean forceNoCitations;
    private UUID forcedRandomCitation;
    private String forcedRecommendation;

    @Override
    public GiftSelection selectGifts(String occasion, List<Scored> guidance, List<AvailableListing> available) {
        callCount.incrementAndGet();

        List<UUID> listingIds = forcedListingIds != null ? forcedListingIds
            : available.stream().limit(3).map(AvailableListing::id).toList();

        List<UUID> citations;
        if (forceNoCitations) {
            citations = List.of();
        } else if (forcedRandomCitation != null) {
            citations = List.of(forcedRandomCitation);
        } else {
            citations = guidance.stream().limit(2).map(Scored::id).toList();
        }

        return new GiftSelection(listingIds, citations,
            "A thoughtful choice for " + occasion + ", chosen from what's actually available near you.");
    }

    @Override
    public Diagnosis diagnose(String symptoms, String species, List<Scored> guidance) {
        callCount.incrementAndGet();

        List<UUID> citations = forceNoCitations ? List.of()
            : forcedRandomCitation != null ? List.of(forcedRandomCitation)
            : guidance.stream().limit(2).map(Scored::id).toList();

        String recommendation = forcedRecommendation != null ? forcedRecommendation
            : "Based on similar cases, adjust watering and isolate the plant from others while it recovers.";

        return new Diagnosis(citations, recommendation);
    }

    public int callCount() {
        return callCount.get();
    }

    public void willReturnListingIds(List<UUID> ids) {
        this.forcedListingIds = ids;
    }

    public void willReturnAnswerWithNoCitations() {
        this.forceNoCitations = true;
    }

    public void willCiteRandomChunkId() {
        this.forcedRandomCitation = UUID.randomUUID();
    }

    public void willRecommend(String recommendation) {
        this.forcedRecommendation = recommendation;
    }

    public void reset() {
        callCount.set(0);
        forcedListingIds = null;
        forceNoCitations = false;
        forcedRandomCitation = null;
        forcedRecommendation = null;
    }
}
