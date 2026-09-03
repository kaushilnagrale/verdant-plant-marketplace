package com.verdant.orders.preview;

import com.verdant.cart.CartLine;

import java.util.List;

public record CartPreviewRequest(List<CartLine> items, String destinationPincode) { }
