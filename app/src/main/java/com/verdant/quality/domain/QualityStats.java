package com.verdant.quality.domain;

/**
 * Raw counts behind one seller's score, over the scoring window - real
 * ground truth (totalOrders/totalDispatches/totalDelivered come from
 * orders/shipments directly) mixed with the audited signal counts
 * (everything else, from seller_quality_events) that only exist because
 * something explicitly recorded them. See QualityStatsRepository's own
 * doc comment for exactly which is which and why.
 */
public record QualityStats(
    int totalOrders,
    int sellerCancellations,
    int totalDispatches,
    int onTimeDispatches,
    int totalDelivered,
    int claimsUpheld,
    int rtoCount,
    int totalOpsRequests,
    int fastResponses
) {
}
