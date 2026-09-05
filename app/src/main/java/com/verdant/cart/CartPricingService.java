package com.verdant.cart;

import com.verdant.catalog.Listing;
import com.verdant.catalog.ListingRepository;
import com.verdant.common.domain.Money;
import com.verdant.identity.domain.Seller;
import com.verdant.identity.domain.SellerStatus;
import com.verdant.identity.repository.SellerRepository;
import com.verdant.inventory.InsufficientStockException;
import com.verdant.inventory.InventoryService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

/**
 * The consistency boundary (Week 9): search may be stale - a plant a
 * buyer found through OpenSearch may have sold out, been deactivated, or
 * had its seller suspended since the index was last refreshed. This
 * class is the real, single choke point that closes that gap, because
 * both real callers go through it: the standalone, pre-checkout
 * POST /v1/cart/quote (CartController), and CheckoutService.checkout()
 * itself, which re-prices the allocated lines through this exact same
 * method right before reserving stock. Every check here reads Postgres
 * fresh on every call - never the search index, never a cached value.
 */
@Service
public class CartPricingService {

    private final ListingRepository listings;
    private final SellerRepository sellers;
    private final InventoryService inventory;

    public CartPricingService(ListingRepository listings, SellerRepository sellers, InventoryService inventory) {
        this.listings = listings;
        this.sellers = sellers;
        this.inventory = inventory;
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

            // Real, fresh Postgres reads - a listing search returned may have
            // been deactivated, sold out, or had its seller suspended since
            // the index was last refreshed. None of this is an error case:
            // a stale hit is normal, not a bug (Week 9) - it just cannot be
            // allowed to proceed to a price or a purchase.
            if (!listing.isActive()) {
                throw new ListingNoLongerAvailableException(listing.getId());
            }
            if (inventory.sellableQuantity(listing.getId()) < line.quantity()) {
                throw new InsufficientStockException(listing.getId());
            }
            Seller seller = sellers.findById(listing.getSellerId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Seller not found: " + listing.getSellerId()));
            if (seller.getStatus() != SellerStatus.APPROVED) {
                throw new SellerUnavailableException(seller.getId());
            }

            Money unitPrice = Money.of(listing.getPriceAmount());
            Money lineTotal = unitPrice.times(line.quantity());

            lineTotals.add(new CartLineTotal(
                listing.getId(), listing.getSellerId(), listing.getRawTitle(), line.quantity(), unitPrice, lineTotal));
            total = total.plus(lineTotal);
        }

        return new CartQuote(lineTotals, total);
    }
}
