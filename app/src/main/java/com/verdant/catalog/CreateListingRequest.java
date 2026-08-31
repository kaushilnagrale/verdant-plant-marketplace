package com.verdant.catalog;

import java.math.BigDecimal;

public record CreateListingRequest(String rawTitle, BigDecimal priceAmount) { }
