package com.verdant.quality;

import com.verdant.common.testing.AllocationFixtures;
import com.verdant.common.testing.IntegrationTestBase;
import com.verdant.quality.domain.Tier;
import com.verdant.quality.service.QualityScoreService;
import com.verdant.quality.service.QualityScorer;
import com.verdant.quality.service.SellerQualityEventRepository;
import com.verdant.quality.service.SellerQualityHistoryRepository;
import com.verdant.quality.service.SellerQualityScoreRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every scenario here seeds seller_quality_events (and, for the ground-
 * truth denominators, real orders/order_items/shipments) DIRECTLY rather
 * than driving them through SellerOrderService/ClaimResolutionService/
 * ShipmentStatusProjector - same "seed the data, test the algorithm"
 * pattern AllocationTest/ReconciliationTest already established. The real
 * PRODUCER wiring (does a real dispatch/claim/RTO actually create the
 * right row) is proven separately, at each producer's own call site
 * (SellerOrderServiceTest, ClaimResolutionTest, ShipmentStatusProjectorTest).
 */
class QualityScorerTest extends IntegrationTestBase {

    @Autowired private QualityScorer scorer;
    @Autowired private QualityScoreService scoreService;
    @Autowired private SellerQualityEventRepository events;
    @Autowired private SellerQualityScoreRepository scores;
    @Autowired private SellerQualityHistoryRepository historyRepo;
    @Autowired private JdbcTemplate jdbc;

    private UUID canonicalProductId;

    private UUID seedSellerListing(UUID sellerId) {
        if (canonicalProductId == null) {
            canonicalProductId = AllocationFixtures.seedCanonicalProduct(jdbc, "Quality Test Plant");
        }
        return AllocationFixtures.seedListing(jdbc, sellerId, canonicalProductId,
            "Quality test listing", "150.00", 100);
    }

    /**
     * totalOrders real DELIVERED orders are seeded (ground truth - see
     * QualityStatsRepository's own comment on why totalOrders/totalDelivered
     * come from here, not from seller_quality_events), each paired with a
     * real DISPATCH_ON_TIME/DISPATCH_LATE event so totalDispatches equals
     * totalOrders by construction - exactly what every runbook test
     * scenario's own arithmetic assumes.
     */
    private UUID sellerWithHistory(int totalOrders, int onTimeDispatches, int cancellations,
                                    int claimsUpheld, int rtoCount) {
        AllocationFixtures.seedLocalPincodeZone(jdbc);
        UUID sellerId = AllocationFixtures.seedApprovedSeller(jdbc, "Quality Test Nursery " + UUID.randomUUID());
        UUID listingId = seedSellerListing(sellerId);

        for (int i = 0; i < totalOrders; i++) {
            UUID orderId = jdbc.queryForObject("""
                INSERT INTO orders (buyer_email, status, total_amount, expires_at, paid_at)
                VALUES ('quality-buyer@example.com', 'PAID', 150.00, now() + interval '15 minutes', now())
                RETURNING id
                """, UUID.class);
            jdbc.update("""
                INSERT INTO order_items (order_id, listing_id, seller_id, quantity, unit_price, line_total)
                VALUES (?, ?, ?, 1, 150.00, 150.00)
                """, orderId, listingId, sellerId);
            jdbc.update("""
                INSERT INTO shipments (order_id, seller_id, status, delivered_at)
                VALUES (?, ?, 'DELIVERED', now())
                """, orderId, sellerId);
            events.record(sellerId, i < onTimeDispatches ? "DISPATCH_ON_TIME" : "DISPATCH_LATE", orderId);
        }
        for (int i = 0; i < cancellations; i++) {
            events.record(sellerId, "ORDER_CANCELLED_BY_SELLER", null);
        }
        for (int i = 0; i < claimsUpheld; i++) {
            events.record(sellerId, "CLAIM_UPHELD", null);
        }
        for (int i = 0; i < rtoCount; i++) {
            events.record(sellerId, "RTO_RECEIVED", null);
        }
        return sellerId;
    }

    @Test
    void score_isExplainable_returningPerComponentContributions() {
        var seller = sellerWithHistory(50, 45, 2, 1, 1);
        var score = scorer.compute(seller);

        assertThat(score.components()).containsKeys(
            "dispatchAdherence", "cancellationRate", "claimRate", "rtoRate", "responsiveness");
        assertThat(score.components().get("dispatchAdherence").description()).isNotBlank();
        assertThat(score.components().get("dispatchAdherence").numerator()).isEqualTo(45);
        assertThat(score.components().get("dispatchAdherence").denominator()).isEqualTo(50);
    }

    @Test
    void newSeller_isProvisional_andStartsNearNeutral() {
        var seller = sellerWithHistory(3, 3, 0, 0, 0);
        var score = scorer.compute(seller);

        assertThat(score.isProvisional()).isTrue();
        assertThat(score.tier()).isEqualTo(Tier.PROBATION);
        assertThat(score.value().doubleValue()).isBetween(0.65, 0.85); // not 1.0 despite a perfect record
    }

    @Test
    void singleBadWeek_doesNotCrossRestrictedThreshold_forOtherwiseGoodSeller() {
        var seller = sellerWithHistory(100, 100, 0, 0, 0);
        scoreService.recompute(seller); // establishes a real "current" baseline near 1.0

        // A genuinely bad week, added on top of the long good history.
        for (int i = 0; i < 20; i++) {
            events.record(seller, "DISPATCH_LATE", null);
        }

        double current = scores.findBySellerId(seller).orElseThrow().score();
        double damped = scorer.applyDamping(current, scorer.compute(seller).value().doubleValue());

        assertThat(scorer.tierFor(damped, false)).isNotEqualTo(Tier.RESTRICTED);
    }

    @Test
    void dampingCapsMovementPerComputation() {
        // Plain double subtraction (0.90 - 0.10 etc.) isn't guaranteed to
        // land on the exact IEEE-754 value closest to the decimal literal
        // 0.80/0.30 - real, found by this exact assertion failing with the
        // runbook's own bare isEqualTo() on a computed double. offset()
        // is the standard, correct way to assert a floating-point result,
        // not a weakening of the check: 0.10 movement is still asserted
        // to 9 decimal places of precision, far tighter than anything
        // that could mask a real damping bug.
        assertThat(scorer.applyDamping(0.90, 0.20)).isEqualTo(0.80, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(scorer.applyDamping(0.20, 0.90)).isEqualTo(0.30, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void upheldClaim_reducesClaimRateComponent() {
        var seller = sellerWithHistory(20, 20, 0, 0, 0);
        assertThat(scorer.compute(seller).components().get("claimRate").value()).isEqualByComparingTo(1.0);

        events.record(seller, "CLAIM_UPHELD", null);

        var claimRate = scorer.compute(seller).components().get("claimRate");
        assertThat(claimRate.numerator()).isEqualTo(1);
        assertThat(claimRate.value()).isLessThan(1.0);
    }

    @Test
    void rejectedClaim_doesNotAffectClaimRateComponent() {
        var seller = sellerWithHistory(20, 20, 0, 0, 0);
        double before = scorer.compute(seller).components().get("claimRate").value();

        events.record(seller, "CLAIM_REJECTED", null);

        var claimRate = scorer.compute(seller).components().get("claimRate");
        assertThat(claimRate.numerator()).isEqualTo(0);
        assertThat(claimRate.value()).isEqualTo(before);
    }

    @Test
    void scoreChange_isRecordedInHistory_withComponents() {
        var seller = sellerWithHistory(30, 28, 1, 1, 0);
        scoreService.recompute(seller);
        for (int i = 0; i < 10; i++) {
            events.record(seller, "DISPATCH_LATE", null);
        }
        scoreService.recompute(seller);

        assertThat(historyRepo.findBySellerId(seller)).hasSize(2);
    }
}
