package com.verdant.seller;

import java.math.BigDecimal;
import java.util.UUID;

public record SellerOrderItem(UUID listingId, String productName, int quantity, BigDecimal lineTotal) { }
