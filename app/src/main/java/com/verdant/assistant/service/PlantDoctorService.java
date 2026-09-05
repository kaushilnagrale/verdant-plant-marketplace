package com.verdant.assistant.service;

import com.verdant.assistant.domain.DocType;
import com.verdant.assistant.domain.DoctorResponse;
import com.verdant.assistant.domain.Outcome;
import com.verdant.assistant.domain.Scored;
import com.verdant.assistant.domain.SymptomDetection;
import com.verdant.assistant.model.Diagnosis;
import com.verdant.assistant.model.PlantAssistantModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Three constraints doing real work (60.2): vision extracts symptoms
 * only, never a diagnosis; species comes from the buyer's real order,
 * never the model guessing at the photo; nothing retrieved above the
 * floor means escalate to a human, not generate - a confident wrong
 * answer about watering kills the plant.
 */
@Service
public class PlantDoctorService {

    private static final double SYMPTOM_CONFIDENCE_FLOOR = 0.55;
    /** Same real, empirically-calibrated value as GiftConciergeService's own floor - see that class's own comment for the real score data behind it (same embedding model, same real content set). */
    private static final double SIMILARITY_FLOOR = 0.45;

    private final VisionService vision;
    private final OrderItemSpeciesResolver species;
    private final EmbeddingModel embeddings;
    private final KnowledgeChunkRepository chunks;
    private final PlantAssistantModel model;
    private final PesticideGuard pesticideGuard;
    private final AssistantInteractionRepository interactions;

    public PlantDoctorService(VisionService vision, OrderItemSpeciesResolver species, EmbeddingModel embeddings,
                               KnowledgeChunkRepository chunks, PlantAssistantModel model,
                               PesticideGuard pesticideGuard, AssistantInteractionRepository interactions) {
        this.vision = vision;
        this.species = species;
        this.embeddings = embeddings;
        this.chunks = chunks;
        this.model = model;
        this.pesticideGuard = pesticideGuard;
        this.interactions = interactions;
    }

    public DoctorResponse diagnose(String userId, String imageKey, String note, UUID orderItemId) {
        long started = System.currentTimeMillis();

        // 1. Vision extracts SYMPTOMS ONLY, never a diagnosis.
        SymptomDetection symptoms = vision.detectSymptoms(imageKey);
        if (symptoms.confidence().doubleValue() < SYMPTOM_CONFIDENCE_FLOOR) {
            return record(userId, note, DoctorResponse.needBetterPhoto(
                "I cannot see the leaves clearly. Could you take a photo in daylight, close enough to see a leaf?"),
                started, null);
        }

        // 2. Species comes from the ORDER, not from the model guessing.
        String speciesName = orderItemId != null ? species.speciesOf(orderItemId) : "UNKNOWN";

        // 3. Retrieve care guidance for these symptoms and this species.
        String query = symptoms.describe() + " " + speciesName + " " + (note == null ? "" : note);
        List<Scored> guidance = chunks.retrieve(embeddings.embed(query), 5,
            DocType.SYMPTOM_GUIDE, DocType.CARE_GUIDE);
        Double topSimilarity = guidance.isEmpty() ? null : guidance.get(0).score().doubleValue();

        if (guidance.isEmpty() || topSimilarity < SIMILARITY_FLOOR) {
            return record(userId, note, DoctorResponse.escalate(
                "I don't have guidance that matches what I'm seeing. I've sent this to our team, "
                    + "they'll reply on WhatsApp."), started, topSimilarity);
        }

        // 4. Model composes from retrieved text only.
        Diagnosis diagnosis = model.diagnose(symptoms.describe(), speciesName, guidance);
        validateGrounded(diagnosis, guidance);
        pesticideGuard.rejectPesticideNaming(diagnosis);

        DoctorResponse response = DoctorResponse.answered(speciesName, diagnosis.recommendation(), diagnosis.citations());
        return record(userId, note, response, started, topSimilarity);
    }

    private void validateGrounded(Diagnosis diagnosis, List<Scored> guidance) {
        Set<UUID> allowedChunks = guidance.stream().map(Scored::id).collect(Collectors.toSet());
        if (diagnosis.citations().isEmpty() || !allowedChunks.containsAll(diagnosis.citations())) {
            throw new UngroundedResponseException("Missing or fabricated citations");
        }
    }

    private DoctorResponse record(String userId, String question, DoctorResponse response, long started, Double topSimilarity) {
        Outcome outcome = response.outcome();
        interactions.record("PLANT_DOCTOR", userId, question == null ? "(photo)" : question, outcome,
            response.citations(), topSimilarity, System.currentTimeMillis() - started);
        return response;
    }
}
