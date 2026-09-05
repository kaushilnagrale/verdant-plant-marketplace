package com.verdant.assistant.model;

import com.verdant.assistant.domain.AvailableListing;
import com.verdant.assistant.domain.Scored;

import java.util.List;

/**
 * The one seam a real LLM integration would plug into. No real chat-
 * completion provider has credentials configured anywhere in this
 * project (unlike the real, local ONNX embedding model already reused
 * from Week 3 for retrieval) - same real, honest treatment as
 * StubPayoutGateway/StubWhatsAppGateway for a real external paid
 * service this project doesn't have an account for.
 *
 * Deliberately NOT Spring AI's own ChatClient: that framework's prompt-
 * template/entity-mapping machinery exists specifically to talk to a
 * real hosted model, and wrapping a stub in it would add real
 * complexity for a component that has nothing on the other end to
 * justify it. A real integration replaces StubPlantAssistantModel with
 * one built on ChatClient, keeping this same interface.
 */
public interface PlantAssistantModel {

    GiftSelection selectGifts(String occasion, List<Scored> guidance, List<AvailableListing> available);

    Diagnosis diagnose(String symptoms, String species, List<Scored> guidance);
}
