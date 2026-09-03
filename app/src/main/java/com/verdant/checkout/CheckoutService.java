package com.verdant.checkout;

import com.verdant.cart.CartLine;
import com.verdant.cart.CartPricingService;
import com.verdant.cart.CartQuote;
import com.verdant.inventory.InsufficientStockException;
import com.verdant.inventory.InventoryService;
import com.verdant.inventory.ReservationLine;
import com.verdant.inventory.ReservationService;
import com.verdant.payment.PaymentGateway;
import com.verdant.payment.RazorpayProperties;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/**
 * Reserving stock (a DB transaction) and creating a payment order (a network
 * call to Razorpay) are deliberately NOT one transaction - holding a DB
 * connection open for the duration of an external HTTP call is a well-known
 * anti-pattern. Instead: reserve locally first (fast, all-or-nothing),
 * call out to Razorpay second, and explicitly compensate (release the
 * reservations) if anything after the reservation step fails. A small saga,
 * not a distributed transaction.
 */
@Service
public class CheckoutService {

    private static final Duration CHECKOUT_WINDOW = Duration.ofMinutes(15);

    private final CartPricingService pricing;
    private final InventoryService inventory;
    private final ReservationService reservations;
    private final PaymentGateway paymentGateway;
    private final RazorpayProperties razorpayProps;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public CheckoutService(CartPricingService pricing, InventoryService inventory,
                            ReservationService reservations, PaymentGateway paymentGateway,
                            RazorpayProperties razorpayProps, JdbcTemplate jdbc,
                            PlatformTransactionManager txManager) {
        this.pricing = pricing;
        this.inventory = inventory;
        this.reservations = reservations;
        this.paymentGateway = paymentGateway;
        this.razorpayProps = razorpayProps;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
    }

    public CheckoutResult checkout(CheckoutRequest request) {
        CartQuote quote = pricing.priceCart(request.lines());
        UUID orderId = UUID.randomUUID();

        reserveStock(orderId, request.lines());

        String razorpayOrderId;
        try {
            razorpayOrderId = paymentGateway.createOrder(quote.total(), orderId.toString());
        } catch (RuntimeException e) {
            reservations.releaseByOrder(orderId);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Payment provider unavailable", e);
        }

        try {
            persistOrder(orderId, request.buyerEmail(), quote, razorpayOrderId);
        } catch (RuntimeException e) {
            reservations.releaseByOrder(orderId);
            throw e;
        }

        return new CheckoutResult(orderId, razorpayOrderId, razorpayProps.keyId(), quote.total());
    }

    private void reserveStock(UUID orderId, List<CartLine> lines) {
        List<ReservationLine> reservationLines = lines.stream()
            .map(l -> new ReservationLine(l.listingId(), l.quantity()))
            .toList();
        try {
            inventory.reserveAll(orderId, reservationLines);
        } catch (InsufficientStockException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /**
     * Multiple related inserts (the order row, and one row per line item)
     * must land together or not at all - a bare sequence of jdbc.update()
     * calls with no active transaction auto-commits each one individually,
     * so a failure partway through (e.g. the 2nd of 3 items) would leave a
     * committed order with only some of its items recorded. TransactionTemplate
     * (not a plain @Transactional - this is a private method called
     * internally, which the Spring AOP proxy would never intercept, per the
     * same self-invocation lesson from Day 26/28) makes this one atomic unit.
     */
    private void persistOrder(UUID orderId, String buyerEmail, CartQuote quote, String razorpayOrderId) {
        tx.executeWithoutResult(status -> {
            Instant expiresAt = Instant.now().plus(CHECKOUT_WINDOW);
            jdbc.update("""
                INSERT INTO orders (id, buyer_email, status, total_amount, currency, razorpay_order_id, expires_at)
                VALUES (?, ?, 'CREATED', ?, ?, ?, ?)
                """, orderId, buyerEmail, quote.total().amount(), quote.total().currency().getCurrencyCode(),
                razorpayOrderId, Timestamp.from(expiresAt));

            // Durable record of what was actually ordered - previously only
            // the aggregate total was persisted, and the per-line breakdown
            // (listing, seller, price) was discarded once CartQuote went
            // out of scope. The allocation engine (Week 6) needs this to
            // route each line to a seller.
            for (var line : quote.lines()) {
                jdbc.update("""
                    INSERT INTO order_items (order_id, listing_id, seller_id, quantity, unit_price, line_total)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, orderId, line.listingId(), line.sellerId(), line.quantity(),
                    line.unitPrice().amount(), line.lineTotal().amount());
            }
        });
    }
}
