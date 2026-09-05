package com.verdant.finance.domain;

import com.verdant.common.domain.Money;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** What a seller actually sees - per-order breakdown plus a totals row that reconciles to net_amount, the amount actually transferred. */
public record PayoutStatement(
    UUID cycleId,
    UUID sellerId,
    LocalDate periodStart,
    LocalDate periodEnd,
    PayoutStatus status,
    List<PayoutLineItem> lines,
    Money grossAmount,
    Money withheldAmount,
    Money netAmount,
    String providerRef
) {
    public static PayoutStatement from(PayoutCycle cycle) {
        return new PayoutStatement(cycle.id(), cycle.sellerId(), cycle.periodStart(), cycle.periodEnd(),
            cycle.status(), cycle.lineItems(), cycle.grossAmount(), cycle.withheldAmount(), cycle.netAmount(),
            cycle.providerRef());
    }
}
