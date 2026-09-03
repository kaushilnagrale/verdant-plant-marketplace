package com.verdant.catalog;

import java.math.BigDecimal;
import java.util.UUID;

public record CatalogListingSummary(UUID id, String title, String productName,
                                     BigDecimal priceAmount, UUID sellerId, String sellerName) { }
