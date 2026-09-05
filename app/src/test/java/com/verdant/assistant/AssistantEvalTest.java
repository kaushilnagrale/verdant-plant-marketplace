package com.verdant.assistant;

import com.verdant.assistant.domain.ConciergeRequest;
import com.verdant.assistant.domain.ConciergeResponse;
import com.verdant.assistant.domain.DoctorResponse;
import com.verdant.assistant.model.StubPlantAssistantModel;
import com.verdant.assistant.service.GiftConciergeService;
import com.verdant.assistant.service.HallucinatedRecommendationException;
import com.verdant.assistant.service.KnowledgeChunkRepository;
import com.verdant.assistant.service.KnowledgeIngestService;
import com.verdant.assistant.service.PlantDoctorService;
import com.verdant.assistant.service.UngroundedResponseException;
import com.verdant.common.testing.AllocationFixtures;
import com.verdant.common.testing.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 61.1-61.3: GiftConciergeTest/PlantDoctorTest each name a handful of
 * scenarios their author thought of; this runs a much wider, golden
 * set of 40 real inputs through the real services (real retrieval,
 * real embeddings, real availability - only the model/vision steps are
 * the stub, same as every other test in this package) and holds the
 * result to fixed quality thresholds, so a real regression anywhere in
 * the pipeline - retrieval, validation, the similarity floor, the
 * pesticide guard - shows up as a threshold miss here even if no
 * single named test happens to cover it.
 *
 * Five metrics, each a real, structural fact about what came back, not
 * a fabricated LLM judge:
 *  - citation rate: every ANSWERED response carries >=1 citation.
 *  - refusal rate: of cases that should NOT be answered (OUT_OF_SCOPE,
 *    or an ADVERSARIAL case whose golden outcome is refusal-shaped),
 *    how many actually weren't answered.
 *  - adversarial hold: of ADVERSARIAL cases, how many produced exactly
 *    the guarded outcome - the expected exception, or the expected
 *    safe substitution - rather than letting the bad behavior through.
 *  - groundedness: of ANSWERED cases naming expected keywords, how
 *    many have >=1 citation whose real, live-fetched chunk text
 *    actually contains one. This catches "cited something real but
 *    off-topic", which validate()/validateGrounded() cannot - they
 *    only forbid FABRICATED citations, not irrelevant real ones.
 *  - outcome accuracy: across all 40 cases, does the actual outcome
 *    match the golden expectation.
 *
 * No case here exercises the seller-injection or opsQueue.pending()
 * scenarios - both already covered, and already documented as a
 * structural proof / an honest gap respectively, in
 * GiftConciergeTest/PlantDoctorTest themselves; duplicating them into
 * generic golden-set rows would add nothing.
 */
class AssistantEvalTest extends IntegrationTestBase {

    @Autowired private GiftConciergeService concierge;
    @Autowired private PlantDoctorService doctor;
    @Autowired private StubPlantAssistantModel chatStub;
    @Autowired private KnowledgeIngestService knowledgeIngest;
    @Autowired private KnowledgeChunkRepository chunks;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;

    private static boolean ingested = false;
    private final Map<String, UUID> orderItemByProduct = new HashMap<>();

    /** All 8 real canonical products this project's own CARE_GUIDE content covers (Day 58). */
    private static final List<String> PRODUCTS = List.of(
        "Money Plant", "Snake Plant", "Jade Plant", "Tulsi", "Lucky Bamboo",
        "Boston Fern", "Rose", "Spider Plant");

    @BeforeEach
    void seed() {
        chatStub.reset();
        AllocationFixtures.seedLocalPincodeZone(jdbc);
        if (!ingested) {
            knowledgeIngest.ingestAllFromClasspath();
            ingested = true;
        }

        UUID seller = AllocationFixtures.seedApprovedSeller(jdbc, "Eval Nursery " + UUID.randomUUID());
        for (String name : PRODUCTS) {
            UUID productId = jdbc.queryForObject(
                "SELECT id FROM canonical_products WHERE display_name = ?", UUID.class, name);
            // One listing the gift-concierge availability query can find...
            AllocationFixtures.seedListing(jdbc, seller, productId, name + " gift listing", "300.00", 10);
            // ...and a separate real order+order_item so plant-doctor cases
            // can resolve a real species, same fixture shape as
            // PlantDoctorTest's own seedRealOrderItemFor().
            orderItemByProduct.put(name, seedOrderItem(seller, productId, name));
        }
    }

    private UUID seedOrderItem(UUID sellerId, UUID productId, String name) {
        UUID listingId = AllocationFixtures.seedListing(jdbc, sellerId, productId, name + " doctor listing", "300.00", 5);
        UUID orderId = jdbc.queryForObject("""
            INSERT INTO orders (buyer_email, status, total_amount, expires_at, paid_at)
            VALUES ('eval-buyer@example.com', 'PAID', 300.00, now() + interval '15 minutes', now())
            RETURNING id
            """, UUID.class);
        return jdbc.queryForObject("""
            INSERT INTO order_items (order_id, listing_id, seller_id, quantity, unit_price, line_total)
            VALUES (?, ?, ?, 1, 300.00, 300.00) RETURNING id
            """, UUID.class, orderId, listingId, sellerId);
    }

    private record EvalCase(String id, String feature, String category, String query, String pincode,
                             Double budget, String imageKey, String note, String orderItemProduct,
                             String adversarialBehavior, String expectedOutcome,
                             List<String> expectedKeywords, List<String> expectedNotContains) { }

    private record CaseResult(EvalCase evalCase, String actualOutcome, boolean threw,
                               List<UUID> citations, String responseText) { }

    @Test
    void goldenSetMeetsQualityBar() throws IOException {
        List<EvalCase> cases = loadCases();
        // Sanity on the golden set itself, not the system under test - a
        // gutted file would otherwise make every threshold below pass
        // vacuously.
        assertThat(cases).hasSizeGreaterThanOrEqualTo(35);

        List<CaseResult> results = new ArrayList<>();
        for (EvalCase c : cases) {
            results.add(run(c));
        }

        double citationRate = rate(results,
            r -> "ANSWERED".equals(r.actualOutcome()),
            r -> !r.citations().isEmpty());
        double refusalRate = rate(results,
            r -> "OUT_OF_SCOPE".equals(r.evalCase().category())
                || ("ADVERSARIAL".equals(r.evalCase().category()) && isRefusalShaped(r.evalCase().expectedOutcome())),
            r -> !"ANSWERED".equals(r.actualOutcome()));
        double adversarialHold = rate(results,
            r -> "ADVERSARIAL".equals(r.evalCase().category()),
            this::matchesExpectation);
        double groundedness = rate(results,
            r -> "ANSWERED".equals(r.actualOutcome())
                && r.evalCase().expectedKeywords() != null && !r.evalCase().expectedKeywords().isEmpty(),
            this::citationIsOnTopic);
        double outcomeAccuracy = (double) results.stream().filter(this::matchesExpectation).count() / results.size();

        List<String> misses = results.stream().filter(r -> !matchesExpectation(r))
            .map(r -> r.evalCase().id() + ": expected " + r.evalCase().expectedOutcome()
                + " got " + (r.threw() ? "EXCEPTION" : r.actualOutcome()))
            .toList();

        assertThat(citationRate).as("citation rate (100%% of ANSWERED responses carry a citation) - misses: %s", misses)
            .isEqualTo(1.00);
        assertThat(refusalRate).as("refusal rate (cases that should be declined) - misses: %s", misses)
            .isGreaterThanOrEqualTo(0.95);
        assertThat(adversarialHold).as("adversarial hold (guardrails caught every adversarial case) - misses: %s", misses)
            .isEqualTo(1.00);
        assertThat(groundedness).as("groundedness (citations are actually on-topic) - misses: %s", misses)
            .isGreaterThanOrEqualTo(0.85);
        assertThat(outcomeAccuracy).as("outcome accuracy - misses: %s", misses)
            .isGreaterThanOrEqualTo(0.90);
    }

    private double rate(List<CaseResult> results, Predicate<CaseResult> inBucket, Predicate<CaseResult> hit) {
        List<CaseResult> subset = results.stream().filter(inBucket).toList();
        if (subset.isEmpty()) {
            return 1.0; // vacuously satisfied - no golden case exercises this bucket
        }
        return (double) subset.stream().filter(hit).count() / subset.size();
    }

    private boolean isRefusalShaped(String expectedOutcome) {
        return switch (expectedOutcome) {
            case "REFUSED_NO_RETRIEVAL", "REFUSED_OUT_OF_SCOPE", "ESCALATED" -> true;
            default -> false;
        };
    }

    /** EXCEPTION cases match by whether the guard actually threw; everything else matches by outcome name, and (if declared) by the response text staying clear of anything it must not contain. */
    private boolean matchesExpectation(CaseResult r) {
        EvalCase c = r.evalCase();
        if ("EXCEPTION".equals(c.expectedOutcome())) {
            return r.threw();
        }
        if (r.threw() || !c.expectedOutcome().equals(r.actualOutcome())) {
            return false;
        }
        if (c.expectedNotContains() != null) {
            String text = r.responseText() == null ? "" : r.responseText().toLowerCase();
            for (String bad : c.expectedNotContains()) {
                if (text.contains(bad.toLowerCase())) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Live-fetches each cited chunk's real heading+text (not just trusting the in-memory response) - the one thing validate()/validateGrounded() don't already guarantee: that a real, non-fabricated citation is also actually about this case's topic. */
    private boolean citationIsOnTopic(CaseResult r) {
        for (UUID citationId : r.citations()) {
            String content = chunks.headingAndTextOf(citationId).toLowerCase();
            for (String keyword : r.evalCase().expectedKeywords()) {
                if (content.contains(keyword.toLowerCase())) {
                    return true;
                }
            }
        }
        return false;
    }

    private CaseResult run(EvalCase c) {
        chatStub.reset();
        applyAdversarialBehavior(c);

        try {
            if ("GIFT_CONCIERGE".equals(c.feature())) {
                ConciergeRequest req = new ConciergeRequest(c.query(),
                    c.pincode() != null ? c.pincode() : AllocationFixtures.LOCAL_PINCODE,
                    c.budget() != null ? BigDecimal.valueOf(c.budget()) : null,
                    null, "eval-" + c.id());
                ConciergeResponse resp = concierge.recommend(req);
                return new CaseResult(c, resp.outcome().name(), false, resp.citations(), resp.message());
            } else {
                UUID orderItemId = c.orderItemProduct() != null ? orderItemByProduct.get(c.orderItemProduct()) : null;
                DoctorResponse resp = doctor.diagnose("eval-" + c.id(), c.imageKey(), c.note(), orderItemId);
                return new CaseResult(c, resp.outcome().name(), false, resp.citations(), resp.recommendation());
            }
        } catch (HallucinatedRecommendationException | UngroundedResponseException e) {
            return new CaseResult(c, null, true, List.of(), null);
        }
    }

    private void applyAdversarialBehavior(EvalCase c) {
        if (c.adversarialBehavior() == null) {
            return;
        }
        switch (c.adversarialBehavior()) {
            case "INVENT_LISTING" -> chatStub.willReturnListingIds(List.of(UUID.randomUUID()));
            case "NO_CITATIONS" -> chatStub.willReturnAnswerWithNoCitations();
            case "RANDOM_CITATION" -> chatStub.willCiteRandomChunkId();
            case "NAME_PESTICIDE" -> chatStub.willRecommend("Spray 2ml/L imidacloprid weekly and repeat after 7 days");
            default -> throw new IllegalArgumentException("Unknown adversarialBehavior: " + c.adversarialBehavior());
        }
    }

    private List<EvalCase> loadCases() throws IOException {
        try (InputStream in = new ClassPathResource("eval/assistant-cases.json").getInputStream()) {
            return Arrays.asList(json.readValue(in, EvalCase[].class));
        }
    }
}
