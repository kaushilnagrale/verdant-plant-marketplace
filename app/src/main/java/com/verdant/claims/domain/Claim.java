package com.verdant.claims.domain;

import com.verdant.common.domain.Money;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Plain record, not JPA - matches this project's established pattern for
 * most non-finance-core domain shapes.
 *
 * faultConfidence is BigDecimal, not double - not because it's money (it
 * isn't), but because ArchitectureRulesTest bans any float/double field in
 * a ..domain.. package outright, and this class lives in one. Caught by
 * that real, pre-existing rule the first time this project built a
 * non-monetary decimal domain field, not assumed safe.
 */
public record Claim(
    UUID id,
    UUID orderItemId,
    UUID shipmentId,
    String buyerSubjectId,
    ClaimType type,
    ClaimStatus status,
    Fault fault,
    BigDecimal faultConfidence,
    Resolution resolution,
    Money resolutionAmount,
    UUID ledgerTxnId,
    Instant submittedAt,
    Instant resolvedAt,
    String resolvedBy
) { }
