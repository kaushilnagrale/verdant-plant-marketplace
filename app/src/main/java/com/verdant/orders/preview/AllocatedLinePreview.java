package com.verdant.orders.preview;

import java.util.UUID;

public record AllocatedLinePreview(UUID listingId, String productName, int quantity) { }
