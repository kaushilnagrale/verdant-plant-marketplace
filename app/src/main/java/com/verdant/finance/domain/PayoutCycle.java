package com.verdant.finance.domain;

import com.verdant.common.domain.Money;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PayoutCycle(
    UUID id,
    UUID sellerId,
    LocalDate periodStart,
    LocalDate periodEnd,
    PayoutStatus status,
    Money grossAmount,
    Money withheldAmount,
    Money netAmount,
    UUID ledgerTxnId,
    String providerRef,
    String idempotencyKey,
    String approvedBy,
    Instant paidAt,
    String failureReason,
    List<PayoutLineItem> lineItems
) {
    public void requireStatus(PayoutStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Payout cycle " + id + " is " + status + ", expected " + expected);
        }
    }
}
