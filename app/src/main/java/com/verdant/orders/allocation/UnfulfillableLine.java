package com.verdant.orders.allocation;

import java.util.UUID;

public record UnfulfillableLine(UUID listingId, String productName, String reason) { }
