package com.verdant.claims.domain;

import java.util.UUID;

public record ItemFacts(UUID orderItemId, int sellerHandlingTimeHours) { }
