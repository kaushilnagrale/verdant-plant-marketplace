package com.verdant.checkout;

import com.verdant.cart.CartLine;
import com.verdant.cart.CartPricingService;
import com.verdant.cart.CartQuote;
import com.verdant.cart.ListingNoLongerAvailableException;
import com.verdant.cart.SellerUnavailableException;
import com.verdant.inventory.InsufficientStockException;
import com.verdant.inventory.InventoryService;
import com.verdant.inventory.ReservationLine;
import com.verdant.inventory.ReservationService;
import com.verdant.orders.allocation.AllocationEngine;
import com.verdant.orders.allocation.AllocationPlan;
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
import java.util.stream.Collectors;

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
    private final AllocationEngine allocation;
    private final PaymentGateway paymentGateway;
    private final RazorpayProperties razorpayProps;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public CheckoutService(CartPricingService pricing, InventoryService inventory,
                            ReservationService reservations, AllocationEngine allocation,
                            PaymentGateway paymentGateway, RazorpayProperties razorpayProps,
                            JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.pricing = pricing;
        this.inventory = inventory;
        this.reservations = reservations;
        this.allocation = allocation;
        this.paymentGateway = paymentGateway;
        this.razorpayProps = razorpayProps;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
    }

    public CheckoutResult checkout(CheckoutRequest request) {
        if (request.destinationPincode() == null || request.destinationPincode().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "destinationPincode is required");
        }

        // Real allocation, not just the pre-payment preview - a real order
        // reserves against whichever seller's listing the engine actually
        // chose (transit/quality/consolidation/cost scored across every
        // APPROVED seller stocking the same canonical product), which may
        // not be the specific listing the buyer clicked. Previously the
        // preview and the real purchase disagreed: preview showed the split,
        // checkout ignored it entirely and reserved the buyer's literal cart
        // lines - the platform's core "route around a bad seller/transit
        // window" thesis never actually applied to a real order.
        AllocationPlan plan = allocation.plan(request.lines(), request.destinationPincode());
        if (plan.requiresBuyerConfirmation()) {
            String reasons = plan.unfulfillable().stream()
                .map(u -> u.productName() + ": " + u.reason())
                .collect(Collectors.joining("; "));
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Cart cannot be fully allocated - " + reasons);
        }

        List<CartLine> allocatedLines = plan.allocated().stream()
            .map(a -> new CartLine(a.listingId(), a.quantity()))
            .toList();

        // Second, independent revalidation against Postgres (Week 9) -
        // allocation just chose these lines from a fresh query of its own,
        // but minutes can genuinely pass between a buyer starting checkout
        // and this line running (address entry, a slow payment step retried).
        // CartPricingService re-checks active/stock/seller-status fresh,
        // every time, same as it does for the standalone pre-checkout quote.
        CartQuote quote;
        try {
            quote = pricing.priceCart(allocatedLines);
        } catch (InsufficientStockException | ListingNoLongerAvailableException | SellerUnavailableException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        UUID orderId = UUID.randomUUID();

        reserveStock(orderId, allocatedLines);

        String razorpayOrderId;
        try {
            razorpayOrderId = paymentGateway.createOrder(quote.total(), orderId.toString());
        } catch (RuntimeException e) {
            reservations.releaseByOrder(orderId);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Payment provider unavailable", e);
        }

        try {
            persistOrder(orderId, request.buyerEmail(), request.destinationPincode(), quote, razorpayOrderId);
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
    private void persistOrder(UUID orderId, String buyerEmail, String destinationPincode,
                               CartQuote quote, String razorpayOrderId) {
        tx.executeWithoutResult(status -> {
            Instant expiresAt = Instant.now().plus(CHECKOUT_WINDOW);
            jdbc.update("""
                INSERT INTO orders (id, buyer_email, status, total_amount, currency, razorpay_order_id,
                                     expires_at, destination_pincode)
                VALUES (?, ?, 'CREATED', ?, ?, ?, ?, ?)
                """, orderId, buyerEmail, quote.total().amount(), quote.total().currency().getCurrencyCode(),
                razorpayOrderId, Timestamp.from(expiresAt), destinationPincode);

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

            // Created already at INVENTORY_RESERVED, not STARTED - reserveStock()
            // above already did that step synchronously, before Razorpay was
            // ever called. The saga's own job (Day 33) picks up from here:
            // waiting for payment, then settlement, then shipment.
            jdbc.update("""
                INSERT INTO order_sagas (order_id, state, furthest_state, next_attempt_at)
                VALUES (?, 'INVENTORY_RESERVED', 'INVENTORY_RESERVED', now())
                """, orderId);
        });
    }
}
