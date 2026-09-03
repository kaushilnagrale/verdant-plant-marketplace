package com.verdant.catalog;

import java.math.BigDecimal;
import java.util.UUID;

public record CatalogListingDetail(UUID id, String title, String productName, String description,
                                    BigDecimal priceAmount, UUID sellerId, String sellerName, int handlingTimeHours) { }
