package com.verdant.buyer.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BuyerOrderDetail(UUID orderId, String status, BigDecimal totalAmount, Instant createdAt,
                                String recipientName, String recipientPhone, String addressLine1, String addressLine2,
                                String city, String state, String pincode,
                                List<BuyerOrderItem> items, List<ShipmentTracking> shipments) { }
