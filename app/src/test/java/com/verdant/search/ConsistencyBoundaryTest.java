package com.verdant.search;

import com.verdant.cart.CartLine;
import com.verdant.cart.CartPricingService;
import com.verdant.cart.CartQuote;
import com.verdant.cart.ListingNoLongerAvailableException;
import com.verdant.checkout.CheckoutRequest;
import com.verdant.checkout.CheckoutService;
import com.verdant.common.testing.AllocationFixtures;
import com.verdant.common.testing.SearchIntegrationTestBase;
import com.verdant.inventory.InsufficientStockException;
import com.verdant.inventory.InventoryService;
import com.verdant.search.projection.ReindexWorker;
import com.verdant.search.projection.SearchReindexQueueRepository;
import com.verdant.search.query.ListingSearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * "Search told the buyer this exists. Search may be stale. The write
 * model is the authority for anything that changes state" (Day 51). This
 * project has no persistent, server-side cart to "add to" (Week 4's own
 * design: POST /v1/cart/quote is a stateless re-pricing of whatever lines
 * the client already holds, and POST /v1/checkout is the one real,
 * state-changing step) - so these tests adapt the runbook's own
 * "addToCart" scenarios to the real choke point that exists instead:
 * CartPricingService.priceCart(), which both the standalone quote
 * endpoint and CheckoutService itself call fresh, every time.
 *
 * Each test deliberately makes the REAL world (Postgres) diverge from the
 * index by changing state directly (raw SQL, or reserve() called as if
 * from an unrelated concurrent buyer) without re-triggering a reindex -
 * simulating exactly the staleness window that exists for real in
 * production between an event firing and ListingProjector/ReindexWorker
 * catching up (Day 49/52).
 */
class ConsistencyBoundaryTest extends SearchIntegrationTestBase {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private OpenSearchClient opensearch;
    @Autowired private SearchReindexQueueRepository reindexQueue;
    @Autowired private ReindexWorker reindexWorker;
    @Autowired private ListingSearchService searchService;
    @Autowired private CartPricingService pricing;
    @Autowired private InventoryService inventory;
    @Autowired private CheckoutService checkout;

    private UUID moneyPlantId;
    private final List<UUID> indexedListingIds = new ArrayList<>();

    @BeforeEach
    void seedZoneAndProduct() {
        AllocationFixtures.seedLocalPincodeZone(jdbc);
        moneyPlantId = jdbc.queryForObject(
            "SELECT id FROM canonical_products WHERE display_name = ?", UUID.class, "Money Plant");
    }

    /** Same real accumulation risk RankingTest/FacetsTest's own @AfterEach guard against - OPENSEARCH.withReuse(true) never cleans the index on its own between runs. */
    @AfterEach
    void deleteIndexedDocuments() throws Exception {
        if (indexedListingIds.isEmpty()) {
            return;
        }
        BulkRequest.Builder request = new BulkRequest.Builder();
        for (UUID id : indexedListingIds) {
            request.operations(op -> op.delete(del -> del.index("listings").id(id.toString())));
        }
        opensearch.bulk(request.build());
    }

    private UUID createListing(UUID sellerId, String priceAmount, int stock) {
        return AllocationFixtures.seedListing(jdbc, sellerId, moneyPlantId,
            "Consistency test listing " + UUID.randomUUID(), priceAmount, stock);
    }

    /** Indexes the listing exactly as it is right now - the test itself controls what happens to Postgres afterward, and whether that change is ever reflected back into the index. */
    private void indexNow(UUID listingId) throws Exception {
        reindexQueue.enqueue(listingId, "TEST_SETUP");
        reindexWorker.drainBatch(List.of(listingId));
        indexedListingIds.add(listingId);
        opensearch.indices().refresh(r -> r.index("listings"));
    }

    @Test
    void staleSearchResult_isRevalidatedAtCartQuote() throws Exception {
        UUID seller = AllocationFixtures.seedSeller(jdbc,
            "Consistency Boundary Nursery " + UUID.randomUUID(), "APPROVED", AllocationFixtures.LOCAL_PINCODE);
        UUID id = createListing(seller, "349.00", 1);
        indexNow(id);

        // Sold out by an unrelated concurrent buyer - the exact same real
        // InventoryService.reserve() a second checkout would call - with
        // no reindex triggered, so the index keeps showing it in stock.
        boolean anotherBuyerGotIt = inventory.reserve(id, UUID.randomUUID(), 1);
        assertThat(anotherBuyerGotIt).isTrue();

        assertThat(searchService.search("money plant", AllocationFixtures.LOCAL_PINCODE).hits())
            .as("the index has not been told the sale happened yet")
            .anyMatch(h -> h.listingId().equals(id));

        assertThatThrownBy(() -> pricing.priceCart(List.of(new CartLine(id, 1))))
            .as("but Postgres, the real authority, correctly refuses to sell what's no longer there")
            .isInstanceOf(InsufficientStockException.class);
    }

    @Test
    void stalePrice_isNeverUsedForCharging() throws Exception {
        UUID seller = AllocationFixtures.seedSeller(jdbc,
            "Consistency Boundary Nursery " + UUID.randomUUID(), "APPROVED", AllocationFixtures.LOCAL_PINCODE);
        UUID id = createListing(seller, "349.00", 5);
        indexNow(id);
        assertThat(searchService.search("money plant", AllocationFixtures.LOCAL_PINCODE).hits())
            .filteredOn(h -> h.listingId().equals(id))
            .first().extracting(h -> h.priceAmount().toPlainString()).isEqualTo("349.00");

        // Direct SQL, deliberately bypassing the real PATCH endpoint (Day
        // 49) that would enqueue a reindex - the index is left genuinely
        // stale at 349.00, exactly the window that exists for real between
        // a price change committing and the projector catching up.
        jdbc.update("UPDATE listings SET price_amount = '449.00' WHERE id = ?", id);

        CartQuote quote = pricing.priceCart(List.of(new CartLine(id, 1)));

        assertThat(quote.lines()).hasSize(1);
        assertThat(quote.lines().get(0).unitPrice().amount())
            .as("pricing reads Postgres directly - it never consulted the still-stale 349.00 in the index")
            .isEqualByComparingTo("449.00");
    }

    @Test
    void suspendedSellerListing_stillIndexed_cannotBeQuoted() throws Exception {
        UUID seller = AllocationFixtures.seedSeller(jdbc,
            "Consistency Boundary Nursery " + UUID.randomUUID(), "APPROVED", AllocationFixtures.LOCAL_PINCODE);
        UUID id = createListing(seller, "349.00", 5);
        indexNow(id);

        // Direct SQL, not SellerOnboardingService.transition() - that real
        // path (Day 49) already enqueues a reindex on suspension, which
        // would defeat the point of this specific scenario: proving the
        // check holds even while the index has NOT caught up yet.
        jdbc.update("UPDATE sellers SET status = 'SUSPENDED' WHERE id = ?", seller);

        assertThat(searchService.search("money plant", AllocationFixtures.LOCAL_PINCODE).hits())
            .as("stale index still thinks this seller's listing is live")
            .anyMatch(h -> h.listingId().equals(id));

        assertThatThrownBy(() -> pricing.priceCart(List.of(new CartLine(id, 1))))
            .isInstanceOf(com.verdant.cart.SellerUnavailableException.class);
    }

    /**
     * Not one of the runbook's own 4 scenarios, but the same real check
     * this same day's work added to CartPricingService (Listing.isActive())
     * - untested code is exactly the kind of gap this project's own
     * standards don't leave sitting once it's directly reachable, and
     * Day 49 already proved deactivation propagates to the index
     * (ProjectionTest); this proves cart pricing doesn't need to wait for
     * that propagation to refuse it.
     */
    @Test
    void deactivatedListing_stillIndexed_cannotBeQuoted() throws Exception {
        UUID seller = AllocationFixtures.seedSeller(jdbc,
            "Consistency Boundary Nursery " + UUID.randomUUID(), "APPROVED", AllocationFixtures.LOCAL_PINCODE);
        UUID id = createListing(seller, "349.00", 5);
        indexNow(id);

        // Direct SQL, not the real POST .../deactivate endpoint - same
        // reasoning as the suspension test above: proving the check holds
        // while the index has NOT caught up yet.
        jdbc.update("UPDATE listings SET active = false WHERE id = ?", id);

        assertThat(searchService.search("money plant", AllocationFixtures.LOCAL_PINCODE).hits())
            .as("stale index still thinks this listing is live")
            .anyMatch(h -> h.listingId().equals(id));

        assertThatThrownBy(() -> pricing.priceCart(List.of(new CartLine(id, 1))))
            .isInstanceOf(ListingNoLongerAvailableException.class);
    }

    /**
     * REAL BUG IN THIS TEST, found by its first run: using the shared
     * "Money Plant" canonical product here (as every other test in this
     * class does) let checkout's own real allocation engine (Week 6)
     * rescue the order with a completely unrelated candidate - some
     * OTHER test class's still-in-stock, LOCAL-zone, Money-Plant-mapped
     * listing, left behind in this same reused Postgres container -
     * instead of correctly finding nothing fulfillable. Harmless for the
     * other 4 tests above (pricing.priceCart() looks up one exact listing
     * id directly, never searches for alternates), but this is the one
     * test that specifically needs to control the FULL universe of
     * possible candidates. Fixed with a private "[Fixture] "-prefixed
     * canonical product (AllocationFixtures.seedCanonicalProduct) that no
     * other test could possibly have a listing mapped to, guaranteeing
     * this seller's listing is the only candidate that could ever be found.
     */
    @Test
    void checkoutRevalidates_evenIfCartQuoteSucceeded() {
        UUID privateProduct = AllocationFixtures.seedCanonicalProduct(jdbc, "Consistency Boundary Exclusive Plant");
        UUID seller = AllocationFixtures.seedSeller(jdbc,
            "Consistency Boundary Nursery " + UUID.randomUUID(), "APPROVED", AllocationFixtures.LOCAL_PINCODE);
        UUID id = AllocationFixtures.seedListing(jdbc, seller, privateProduct,
            "Consistency test listing " + UUID.randomUUID(), "349.00", 1);

        // The quote step succeeds - nothing wrong with the cart at this instant.
        CartQuote quote = pricing.priceCart(List.of(new CartLine(id, 1)));
        assertThat(quote.total().amount()).isEqualByComparingTo("349.00");

        // Minutes pass (a buyer fills in an address); someone else buys the
        // last unit in the meantime.
        assertThat(inventory.reserve(id, UUID.randomUUID(), 1)).isTrue();

        // Checkout independently re-validates - it does not trust that an
        // earlier, now-stale quote is still good. Surfaces as a 409 either
        // at allocation (no candidate has stock left) or at CheckoutService's
        // own second pricing.priceCart() call right before reservation -
        // both are the same real thesis: checkout never skips its own fresh
        // check just because an earlier step already passed one.
        assertThatThrownBy(() -> checkout.checkout(
                new CheckoutRequest("buyer@example.com", List.of(new CartLine(id, 1)), AllocationFixtures.LOCAL_PINCODE)))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(409));
    }
}
