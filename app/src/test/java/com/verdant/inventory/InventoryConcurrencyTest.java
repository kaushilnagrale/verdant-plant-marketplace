package com.verdant.inventory;

import com.verdant.common.testing.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves (and, once fixed, disproves) the lost-update problem: many
 * concurrent buyers reserving stock on the same listing at the same instant
 * must never be able to reserve more units than actually exist.
 */
class InventoryConcurrencyTest extends IntegrationTestBase {

    private static final int STARTING_STOCK = 50;
    private static final int CONCURRENT_BUYERS = 100;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID listingId;

    @BeforeEach
    void seedListingWithStock() {
        UUID sellerId = UUID.randomUUID();
        listingId = UUID.randomUUID();

        jdbc.update("INSERT INTO sellers (id, business_name, status) VALUES (?, ?, 'APPROVED')",
            sellerId, "Concurrency Test Nursery");
        jdbc.update("""
            INSERT INTO listings (id, seller_id, raw_title, price_amount)
            VALUES (?, ?, 'Concurrency Test Plant', 100.00)
            """, listingId, sellerId);
        jdbc.update("INSERT INTO inventory (listing_id, quantity, buffer) VALUES (?, ?, 0)",
            listingId, STARTING_STOCK);
    }

    @Test
    void concurrentReservations_neverExceedAvailableStock() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(CONCURRENT_BUYERS);
        AtomicInteger successfulReservations = new AtomicInteger();
        AtomicInteger unexpectedFailures = new AtomicInteger();

        for (int i = 0; i < CONCURRENT_BUYERS; i++) {
            UUID orderId = UUID.randomUUID();
            pool.submit(() -> {
                try {
                    start.await();
                    if (inventory.reserve(listingId, orderId, 1)) {
                        successfulReservations.incrementAndGet();
                    }
                    // a request for stock that isn't there must return false
                    // cleanly - never throw
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    unexpectedFailures.incrementAndGet();
                } finally {
                    finished.countDown();
                }
            });
        }

        start.countDown();
        finished.await();
        pool.shutdown();

        Integer remaining = jdbc.queryForObject(
            "SELECT quantity FROM inventory WHERE listing_id = ?", Integer.class, listingId);

        assertThat(successfulReservations.get())
            .as("%d buyers competed for %d units of stock - successes must not exceed stock",
                CONCURRENT_BUYERS, STARTING_STOCK)
            .isLessThanOrEqualTo(STARTING_STOCK);
        assertThat(remaining)
            .as("stock must never be driven negative")
            .isGreaterThanOrEqualTo(0);
        assertThat(unexpectedFailures.get())
            .as("a sold-out reservation attempt must return false cleanly, never throw")
            .isZero();
    }
}
