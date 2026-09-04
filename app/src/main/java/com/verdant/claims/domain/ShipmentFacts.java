package com.verdant.claims.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Not a JPA entity, deliberately - matches this project's established
 * pattern (shipments is a plain JdbcTemplate-backed table, no rich domain
 * object anywhere else either) - just the facts FaultAttributor actually
 * needs, read once by ClaimService before attribution runs.
 */
public record ShipmentFacts(
    UUID shipmentId,
    LocalDate plannedDispatchDate,
    Instant dispatchedAt,
    Instant deliveredAt,
    int transitDaysEstimate
) { }
