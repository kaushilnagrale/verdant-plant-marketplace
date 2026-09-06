package com.verdant.buyer.domain;

import java.math.BigDecimal;
import java.util.UUID;

public record BuyerOrderItem(UUID listingId, String productName, String sellerBusinessName, int quantity, BigDecimal lineTotal) { }
