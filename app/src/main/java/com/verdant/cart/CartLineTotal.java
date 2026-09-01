package com.verdant.cart;

import com.verdant.common.domain.Money;

import java.util.UUID;

/** Server-computed price for one cart line - unitPrice always comes from the listing, never from the request. */
public record CartLineTotal(UUID listingId, String rawTitle, int quantity, Money unitPrice, Money lineTotal) { }
