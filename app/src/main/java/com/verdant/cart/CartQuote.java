package com.verdant.cart;

import com.verdant.common.domain.Money;

import java.util.List;

public record CartQuote(List<CartLineTotal> lines, Money total) { }
