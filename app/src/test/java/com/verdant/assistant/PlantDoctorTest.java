package com.verdant.assistant;

import com.verdant.assistant.domain.Outcome;
import com.verdant.assistant.model.StubPlantAssistantModel;
import com.verdant.assistant.service.KnowledgeIngestService;
import com.verdant.assistant.service.PlantDoctorService;
import com.verdant.assistant.service.UngroundedResponseException;
import com.verdant.common.testing.AllocationFixtures;
import com.verdant.common.testing.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlantDoctorTest extends IntegrationTestBase {

    @Autowired private PlantDoctorService doctor;
    @Autowired private StubPlantAssistantModel chatStub;
    @Autowired private KnowledgeIngestService knowledgeIngest;
    @Autowired private JdbcTemplate jdbc;

    private static boolean ingested = false;
    private UUID fernOrderItem;

    @BeforeEach
    void seed() {
        chatStub.reset();
        AllocationFixtures.seedLocalPincodeZone(jdbc);
        if (!ingested) {
            knowledgeIngest.ingestAllFromClasspath();
            ingested = true;
        }
        fernOrderItem = seedRealOrderItemFor("Boston Fern");
    }

    /** Real order_items row, referencing a real canonical product - OrderItemSpeciesResolver's own real join needs this to exist, not a fake id. */
    private UUID seedRealOrderItemFor(String productDisplayName) {
        UUID sellerId = AllocationFixtures.seedApprovedSeller(jdbc, "Doctor Test Nursery " + UUID.randomUUID());
        UUID productId = jdbc.queryForObject(
            "SELECT id FROM canonical_products WHERE display_name = ?", UUID.class, productDisplayName);
        UUID listingId = AllocationFixtures.seedListing(jdbc, sellerId, productId, productDisplayName, "300.00", 5);
        UUID orderId = jdbc.queryForObject("""
            INSERT INTO orders (buyer_email, status, total_amount, expires_at, paid_at)
            VALUES ('doctor-buyer@example.com', 'PAID', 300.00, now() + interval '15 minutes', now())
            RETURNING id
            """, UUID.class);
        return jdbc.queryForObject("""
            INSERT INTO order_items (order_id, listing_id, seller_id, quantity, unit_price, line_total)
            VALUES (?, ?, ?, 1, 300.00, 300.00) RETURNING id
            """, UUID.class, orderId, listingId, sellerId);
    }

    @Test
    void unclearImage_requestsBetterPhoto_ratherThanGuessing() {
        var response = doctor.diagnose("test-user", "blurry.jpg", null, null);

        assertThat(response.outcome()).isEqualTo(Outcome.REFUSED_NO_RETRIEVAL);
        assertThat(response.message()).contains("daylight");
        assertThat(chatStub.callCount()).isZero();
    }

    @Test
    void everyDiagnosis_carriesAtLeastOneCitation() {
        var response = doctor.diagnose("test-user", "yellow-leaves.jpg", null, fernOrderItem);

        assertThat(response.outcome()).isEqualTo(Outcome.ANSWERED);
        assertThat(response.citations()).isNotEmpty();
    }

    @Test
    void modelCitingUnretrievedChunk_isRejected() {
        chatStub.willCiteRandomChunkId();

        assertThatThrownBy(() -> doctor.diagnose("test-user", "yellow-leaves.jpg", null, fernOrderItem))
            .isInstanceOf(UngroundedResponseException.class);
    }

    @Test
    void pesticideNaming_isReplacedWithGenericGuidance() {
        chatStub.willRecommend("Spray 2ml/L imidacloprid weekly");

        var response = doctor.diagnose("test-user", "pest.jpg", null, fernOrderItem);

        assertThat(response.recommendation()).doesNotContainIgnoringCase("imidacloprid");
        assertThat(response.recommendation()).containsIgnoringCase("isolate");
    }

    @Test
    void speciesComesFromOrder_notFromModelGuess() {
        var response = doctor.diagnose("test-user", "yellow-leaves.jpg", null, fernOrderItem);

        assertThat(response.species()).isEqualTo("Nephrolepis exaltata"); // Boston Fern's real botanical name (V8)
    }

    @Test
    void noOrderItem_stillDiagnoses_withUnknownSpecies() {
        var response = doctor.diagnose("test-user", "yellow-leaves.jpg", null, null);

        assertThat(response.outcome()).isEqualTo(Outcome.ANSWERED);
        assertThat(response.species()).isEqualTo("UNKNOWN");
    }
}
