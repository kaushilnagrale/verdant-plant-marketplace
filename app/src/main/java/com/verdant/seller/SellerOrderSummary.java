package com.verdant.seller;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SellerOrderSummary(UUID orderId, String orderStatus, String buyerEmail,
                                  List<SellerOrderItem> items, String shipmentStatus, Instant createdAt) { }
