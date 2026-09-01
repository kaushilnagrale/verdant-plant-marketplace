package com.verdant.checkout;

import com.verdant.cart.CartLine;

import java.util.List;

public record CheckoutRequest(String buyerEmail, List<CartLine> lines) { }
