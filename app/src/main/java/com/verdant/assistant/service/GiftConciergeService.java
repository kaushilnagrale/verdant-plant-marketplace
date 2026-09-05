package com.verdant.assistant.service;

import com.verdant.assistant.domain.AvailableListing;
import com.verdant.assistant.domain.ConciergeRequest;
import com.verdant.assistant.domain.ConciergeResponse;
import com.verdant.assistant.domain.DocType;
import com.verdant.assistant.domain.Outcome;
import com.verdant.assistant.domain.Scored;
import com.verdant.assistant.model.GiftSelection;
import com.verdant.assistant.model.PlantAssistantModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Two stage design (59.1): retrieval finds the cultural guidance, a
 * DETERMINISTIC query (AvailabilityService) finds what can actually be
 * bought and delivered, and the model only ever selects from that
 * already-decided list and writes the explanation - it can never add to
 * either list. validate() is the whole feature (59.1's own emphasis):
 * without it, a hallucination reaches a customer; with it, it becomes an
 * exception instead.
 */
@Service
public class GiftConciergeService {

    /**
     * Empirically calibrated against this project's own real embedding
     * model and real content (not guessed): real occasion queries this
     * content actually covers (housewarming, Diwali, Griha Pravesh)
     * score 0.49-0.65 top-hit similarity; genuinely uncovered occasions
     * (a dentist's retirement, a bar mitzvah - the runbook's own
     * adversarial eval case, gc-10) top out at 0.36-0.38. 0.45 sits
     * cleanly in the real gap between those two real clusters.
     */
    private static final double SIMILARITY_FLOOR = 0.45;

    private final ScopeClassifier scopeClassifier;
    private final EmbeddingModel embeddings;
    private final KnowledgeChunkRepository chunks;
    private final AvailabilityService availability;
    private final PlantAssistantModel model;
    private final AssistantInteractionRepository interactions;

    public GiftConciergeService(ScopeClassifier scopeClassifier, EmbeddingModel embeddings,
                                 KnowledgeChunkRepository chunks, AvailabilityService availability,
                                 PlantAssistantModel model, AssistantInteractionRepository interactions) {
        this.scopeClassifier = scopeClassifier;
        this.embeddings = embeddings;
        this.chunks = chunks;
        this.availability = availability;
        this.model = model;
        this.interactions = interactions;
    }

    public ConciergeResponse recommend(ConciergeRequest req) {
        long started = System.currentTimeMillis();

        // 1. Out of scope questions never reach retrieval.
        if (scopeClassifier.isOutOfScope(req.query())) {
            return record(req, ConciergeResponse.outOfScope(
                "I can help you choose a plant gift. For anything else, our team is on WhatsApp."), started, null);
        }

        // 2. Retrieve cultural and occasion guidance.
        List<Scored> guidance = chunks.retrieve(embeddings.embed(req.query()), 6,
            DocType.OCCASION_GUIDE, DocType.CULTURAL_NOTE);
        Double topSimilarity = guidance.isEmpty() ? null : guidance.get(0).score().doubleValue();

        if (guidance.isEmpty() || topSimilarity < SIMILARITY_FLOOR) {
            return record(req, ConciergeResponse.noGuidance(
                "I don't have specific guidance for that occasion. Here are our most popular gift plants.",
                popularFallback(req)), started, topSimilarity);
        }

        // 3. DETERMINISTIC: what can actually be bought and delivered alive.
        String guidanceText = guidance.stream().map(Scored::text).collect(Collectors.joining(" "));
        Set<String> productNames = availability.productNamesMentionedIn(guidanceText);
        List<AvailableListing> available = availability.find(
            productNames, req.destinationPincode(), req.budget(), req.requestedDeliveryDate());

        if (available.isEmpty()) {
            return record(req, ConciergeResponse.nothingDeliverable(
                "Nothing we can deliver to " + req.destinationPincode()
                    + " right now would survive the journey for this occasion. Try a nearby zone or a later date."),
                started, topSimilarity);
        }

        // 4. Model selects from THIS list and explains. It cannot add to it.
        GiftSelection selection = model.selectGifts(req.query(), guidance, available);
        validate(selection, available, guidance);

        List<AvailableListing> chosen = available.stream()
            .filter(a -> selection.listingIds().contains(a.id()))
            .toList();
        return record(req, ConciergeResponse.answered(chosen, selection.citations(), selection.explanation()),
            started, topSimilarity);
    }

    /** The whole feature (59.1's own words) - without this, the model's own output reaches a customer unchecked. */
    private void validate(GiftSelection selection, List<AvailableListing> available, List<Scored> guidance) {
        Set<UUID> allowedListings = available.stream().map(AvailableListing::id).collect(Collectors.toSet());
        if (!allowedListings.containsAll(selection.listingIds())) {
            throw new HallucinatedRecommendationException(selection.listingIds());
        }

        Set<UUID> allowedChunks = guidance.stream().map(Scored::id).collect(Collectors.toSet());
        if (selection.citations().isEmpty() || !allowedChunks.containsAll(selection.citations())) {
            throw new UngroundedResponseException("Missing or fabricated citations");
        }
    }

    /** No real cultural guidance retrieved above the floor - falls back to a plain, deterministic "popular" list rather than letting the model guess at a tradition it has no real grounding for. */
    private List<AvailableListing> popularFallback(ConciergeRequest req) {
        return availability.find(
            Set.of("Money Plant", "Snake Plant", "Jade Plant"),
            req.destinationPincode(), req.budget(), req.requestedDeliveryDate());
    }

    private ConciergeResponse record(ConciergeRequest req, ConciergeResponse response, long started, Double topSimilarity) {
        interactions.record("GIFT_CONCIERGE", req.userSubjectId(), req.query(), response.outcome(),
            response.citations(), topSimilarity, System.currentTimeMillis() - started);
        return response;
    }
}
