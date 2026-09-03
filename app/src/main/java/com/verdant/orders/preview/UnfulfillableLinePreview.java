package com.verdant.orders.preview;

import java.util.UUID;

public record UnfulfillableLinePreview(UUID listingId, String productName, String reason) { }
