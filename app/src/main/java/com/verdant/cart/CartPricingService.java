package com.verdant.cart;

import com.verdant.catalog.Listing;
import com.verdant.catalog.ListingRepository;
import com.verdant.common.domain.Money;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

@Service
public class CartPricingService {

    private final ListingRepository listings;

    public CartPricingService(ListingRepository listings) {
        this.listings = listings;
    }

    /**
     * The only source of truth for price is the listing row looked up here.
     * CartLine never carries a price - there is nothing in the request for a
     * tampered client to override.
     */
    public CartQuote priceCart(List<CartLine> cartLines) {
        List<CartLineTotal> lineTotals = new ArrayList<>();
        Money total = Money.ZERO;

        for (CartLine line : cartLines) {
            if (line.quantity() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quantity must be positive");
            }
            Listing listing = listings.findById(line.listingId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Listing not found: " + line.listingId()));

            Money unitPrice = Money.of(listing.getPriceAmount());
            Money lineTotal = unitPrice.times(line.quantity());

            lineTotals.add(new CartLineTotal(
                listing.getId(), listing.getRawTitle(), line.quantity(), unitPrice, lineTotal));
            total = total.plus(lineTotal);
        }

        return new CartQuote(lineTotals, total);
    }
}
