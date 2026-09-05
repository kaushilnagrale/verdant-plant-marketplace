package com.verdant.finance.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One row per calendar day (IST) reconciliation was attempted for - same shape as BalanceSnapshot (Day 29): a plain record over raw JdbcTemplate, no JPA ceremony needed for something this simple. */
public record ReconciliationRun(
    UUID id,
    LocalDate runDate,
    ReconciliationStatus status,
    int providerCount,
    int internalCount,
    int matched,
    int breaksFound,
    Instant startedAt,
    Instant completedAt
) {
}
