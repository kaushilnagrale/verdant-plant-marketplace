package com.verdant.buyer.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BuyerOrderSummary(UUID orderId, String status, BigDecimal totalAmount, Instant createdAt, List<BuyerOrderItem> items) { }
