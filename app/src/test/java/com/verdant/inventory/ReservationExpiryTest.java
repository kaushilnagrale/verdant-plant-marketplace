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

class ReservationExpiryTest extends IntegrationTestBase {

    @Autowired
    private ReservationService reservations;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID listingId;

    @BeforeEach
    void seedListingWithStock() {
        UUID sellerId = UUID.randomUUID();
        listingId = UUID.randomUUID();

        jdbc.update("INSERT INTO sellers (id, business_name, status) VALUES (?, ?, 'APPROVED')",
            sellerId, "Expiry Test Nursery");
        jdbc.update("""
            INSERT INTO listings (id, seller_id, raw_title, price_amount)
            VALUES (?, ?, 'Expiry Test Plant', 100.00)
            """, listingId, sellerId);
        jdbc.update("INSERT INTO inventory (listing_id, quantity, buffer) VALUES (?, 10, 0)",
            listingId);
    }

    private UUID insertReservation(int quantity, boolean expired) {
        UUID id = UUID.randomUUID();
        String expiresAt = expired ? "now() - interval '1 minute'" : "now() + interval '1 hour'";
        jdbc.update("""
            INSERT INTO reservations (id, order_id, listing_id, quantity, expires_at)
            VALUES (?, ?, ?, ?, %s)
            """.formatted(expiresAt), id, UUID.randomUUID(), listingId, quantity);
        return id;
    }

    private Integer currentStock() {
        return jdbc.queryForObject(
            "SELECT quantity FROM inventory WHERE listing_id = ?", Integer.class, listingId);
    }

    @Test
    void releaseExpired_creditsStockBack_andLeavesActiveReservationsAlone() {
        UUID expired = insertReservation(2, true);
        UUID active = insertReservation(3, false);

        // releaseExpired() sweeps every expired reservation platform-wide (the
        // correct real behavior), so a container reused across earlier test
        // runs may have unrelated leftover expired rows too - assert on this
        // test's own listing/reservation, not on a fragile exact global count.
        int released = reservations.releaseExpired();

        assertThat(released).isGreaterThanOrEqualTo(1);
        assertThat(currentStock()).isEqualTo(12); // 10 + 2 credited back

        Boolean expiredIsReleased = jdbc.queryForObject(
            "SELECT released_at IS NOT NULL FROM reservations WHERE id = ?", Boolean.class, expired);
        Boolean activeIsReleased = jdbc.queryForObject(
            "SELECT released_at IS NOT NULL FROM reservations WHERE id = ?", Boolean.class, active);

        assertThat(expiredIsReleased).isTrue();
        assertThat(activeIsReleased).isFalse();
    }

    @Test
    void releaseExpired_neverTouchesAFulfilledReservation() {
        // Simulates a reservation whose payment already succeeded (the
        // checkout flow will set fulfilled_at for real). Even though its
        // window has passed, it must never be swept back onto the shelf -
        // that stock was legitimately sold.
        UUID fulfilled = insertReservation(4, true);
        jdbc.update("UPDATE reservations SET fulfilled_at = now() WHERE id = ?", fulfilled);

        assertThat(reservations.release(fulfilled)).isFalse();
        assertThat(currentStock()).isEqualTo(10); // untouched, no credit back

        reservations.releaseExpired();
        assertThat(currentStock()).isEqualTo(10); // still untouched after a sweep
    }

    @Test
    void release_isIdempotent_secondCallCreditsNothing() {
        // Calls release() directly on one known reservation rather than going
        // through the global releaseExpired() sweep, so this stays exact and
        // deterministic regardless of leftover state from other test runs.
        UUID expired = insertReservation(2, true);

        assertThat(reservations.release(expired)).isTrue();
        assertThat(currentStock()).isEqualTo(12);

        assertThat(reservations.release(expired)).isFalse(); // already released
        assertThat(currentStock()).isEqualTo(12); // not credited twice
    }

    @Test
    void concurrentRelease_ofSameReservation_creditsStockOnlyOnce() throws InterruptedException {
        UUID expired = insertReservation(5, true);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(2);
        AtomicInteger successfulReleases = new AtomicInteger();

        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (reservations.release(expired)) {
                        successfulReleases.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            });
        }

        start.countDown();
        finished.await();
        pool.shutdown();

        assertThat(successfulReleases.get())
            .as("only one of two concurrent releases of the same reservation should win")
            .isEqualTo(1);
        assertThat(currentStock())
            .as("stock must be credited back exactly once, not once per attempt")
            .isEqualTo(15); // 10 + 5, not 10 + 5 + 5
    }
}
