package com.verdant.finance.domain;

import com.verdant.common.domain.Money;

import java.util.UUID;

/**
 * A per-item statement breakdown for the seller's own understanding, not
 * the source of truth for how much money moves - that's the real
 * sellerPayable ledger balance, protected by PayoutService's own balance
 * check. Recomputed here at item granularity using the same rate lookup
 * settlement used at order-seller granularity, so a multi-item order for
 * one seller can differ from the original settlement's own numbers by a
 * paisa or two of independent rounding - a known, harmless reporting
 * nuance (the actual transfer is bounded by the real ledger balance
 * regardless of what this statement adds up to).
 */
public record PayoutLineItem(
    UUID orderId,
    UUID orderItemId,
    Money gross,
    Money commission,
    Money tcs,
    Money gatewayFee,
    Money claimAdjustment,
    Money net
) { }
