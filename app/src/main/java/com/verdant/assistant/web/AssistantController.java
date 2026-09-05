package com.verdant.assistant.web;

import com.verdant.assistant.domain.ConciergeRequest;
import com.verdant.assistant.domain.ConciergeResponse;
import com.verdant.assistant.domain.DoctorResponse;
import com.verdant.assistant.service.GiftConciergeService;
import com.verdant.assistant.service.HallucinatedRecommendationException;
import com.verdant.assistant.service.PlantDoctorService;
import com.verdant.assistant.service.UngroundedResponseException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Buyer-facing, unauthenticated like /v1/catalog and /v1/search - not in
 * either runbook week's own sketch (both go straight from the service to
 * a test), built here because neither feature is actually usable without
 * a real endpoint, same reasoning as Day 50's own SearchController.
 *
 * A HallucinatedRecommendationException/UngroundedResponseException
 * reaching here is deliberately a 500, not caught and turned into a
 * softer response: it means the real validation guard already did its
 * job (stopped a bad answer from being trusted) and something is
 * genuinely wrong with the assistant, worth surfacing loudly rather
 * than silently downgrading to what would look like a normal refusal.
 */
@RestController
@RequestMapping("/v1/assistant")
public class AssistantController {

    private final GiftConciergeService concierge;
    private final PlantDoctorService doctor;

    public AssistantController(GiftConciergeService concierge, PlantDoctorService doctor) {
        this.concierge = concierge;
        this.doctor = doctor;
    }

    @PostMapping("/gift-concierge")
    public ConciergeResponse giftConcierge(@RequestBody ConciergeRequest request) {
        return concierge.recommend(request);
    }

    @PostMapping("/plant-doctor")
    public DoctorResponse plantDoctor(@RequestBody PlantDoctorRequest request) {
        return doctor.diagnose(request.userId(), request.imageKey(), request.note(), request.orderItemId());
    }

    public record PlantDoctorRequest(String userId, String imageKey, String note, UUID orderItemId) { }

    @ExceptionHandler({HallucinatedRecommendationException.class, UngroundedResponseException.class})
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public void handleValidationFailure() { }
}
