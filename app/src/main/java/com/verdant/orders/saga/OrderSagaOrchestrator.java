package com.verdant.orders.saga;

import com.verdant.common.domain.Money;
import com.verdant.finance.service.SettlementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Driven by a poller reading persisted state, so a process crash resumes
 * rather than losing the order. Each advance is its own transaction, which
 * is the whole point of a saga.
 *
 * Adapted from the runbook's model: CheckoutService already reserves
 * inventory synchronously before a saga row is even created, and Razorpay
 * auto-captures on successful payment rather than us actively calling a
 * "capture" API - so this orchestrator's real job starts by POLLING the
 * order's own status for whether the buyer's payment has landed yet (via
 * the client callback or the webhook, both built in Week 4), not driving it.
 *
 * Also: the runbook's own advanceOne() is @Transactional but called via
 * this.advanceOne from within the same bean (findReady(...).forEach(this::advanceOne))
 * - a real self-invocation bug (Spring's proxy never intercepts that call,
 * same lesson as Day 26/28) that would have silently run every step
 * non-transactionally. TransactionTemplate sidesteps it entirely.
 */
@Component
public class OrderSagaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaOrchestrator.class);
    private static final int MAX_ATTEMPTS = 8;
    private static final int MAX_COMPENSATION_ATTEMPTS = 8;

    private final OrderSagaRepository sagas;
    private final JdbcTemplate jdbc;
    private final SettlementService settlement;
    private final LogisticsGateway logistics;
    private final SagaCompensator compensator;
    private final TransactionTemplate tx;

    public OrderSagaOrchestrator(OrderSagaRepository sagas, JdbcTemplate jdbc, SettlementService settlement,
                                  LogisticsGateway logistics, SagaCompensator compensator,
                                  PlatformTransactionManager txManager) {
        this.sagas = sagas;
        this.jdbc = jdbc;
        this.settlement = settlement;
        this.logistics = logistics;
        this.compensator = compensator;
        this.tx = new TransactionTemplate(txManager);
    }

    @Scheduled(fixedDelayString = "${verdant.saga.interval-ms:1000}")
    public void advanceReady() {
        sagas.findReady(Instant.now(), PageRequest.of(0, 50)).forEach(this::advanceOne);
    }

    public void advanceOne(OrderSaga saga) {
        tx.executeWithoutResult(status -> {
            try {
                switch (saga.getState()) {
                    case STARTED, INVENTORY_RESERVED -> checkPaymentCaptured(saga);
                    case PAYMENT_CAPTURED -> postSettlement(saga);
                    case SETTLEMENT_POSTED -> bookShipment(saga);
                    case SHIPMENT_BOOKED -> saga.moveTo(SagaState.COMPLETED);
                    case COMPENSATING -> compensator.compensate(saga);
                    default -> { }
                }
                saga.resetAttempts();

            } catch (RetryableException e) {
                saga.scheduleRetry(backoff(saga.getAttempts()), e.getMessage());
                if (saga.getState() == SagaState.COMPENSATING) {
                    // Never silently give up on undoing a charge - alert
                    // loudly but keep retrying compensation on schedule
                    // rather than moving anywhere terminal.
                    if (saga.getAttempts() >= MAX_COMPENSATION_ATTEMPTS) {
                        log.error("MANUAL INTERVENTION: saga {} stuck compensating: {}",
                            saga.getOrderId(), e.getMessage());
                    }
                } else if (saga.getAttempts() >= MAX_ATTEMPTS) {
                    saga.moveTo(SagaState.COMPENSATING);
                }

            } catch (FatalException e) {
                saga.recordError(e.getMessage());
                saga.moveTo(SagaState.COMPENSATING);
            }

            sagas.save(saga);
        });
    }

    private void checkPaymentCaptured(OrderSaga saga) {
        Map<String, Object> order = jdbc.queryForMap(
            "SELECT status, expires_at FROM orders WHERE id = ?", saga.getOrderId());
        String status = (String) order.get("status");

        if ("PAID".equals(status)) {
            saga.moveTo(SagaState.PAYMENT_CAPTURED);
            return;
        }
        if ("EXPIRED".equals(status) || "CANCELLED".equals(status)) {
            throw new FatalException("Order " + status + " without payment");
        }
        Instant expiresAt = ((Timestamp) order.get("expires_at")).toInstant();
        if (Instant.now().isAfter(expiresAt)) {
            throw new FatalException("Checkout window passed without payment");
        }
        throw new RetryableException("Waiting for payment");
    }

    /**
     * An order can now span multiple sellers (order_items, Day 32) - settles
     * once per seller, each seller's own subtotal. There is no buyer
     * identity system yet (checkout is email-only, no buyer accounts), so
     * the ledger's buyer account is derived deterministically from the
     * email - the same buyer reusing an email gets the same ledger account
     * without needing a whole buyer-identity subsystem built just for this.
     */
    private void postSettlement(OrderSaga saga) {
        List<Map<String, Object>> perSeller = jdbc.queryForList("""
            SELECT seller_id, SUM(line_total) AS seller_gross
              FROM order_items WHERE order_id = ? GROUP BY seller_id
            """, saga.getOrderId());
        String buyerEmail = jdbc.queryForObject(
            "SELECT buyer_email FROM orders WHERE id = ?", String.class, saga.getOrderId());
        UUID buyerId = UUID.nameUUIDFromBytes(buyerEmail.getBytes(StandardCharsets.UTF_8));

        try {
            for (Map<String, Object> row : perSeller) {
                UUID sellerId = (UUID) row.get("seller_id");
                Money gross = Money.of((BigDecimal) row.get("seller_gross"));
                settlement.settle(saga.getOrderId(), buyerId, sellerId, gross, LocalDate.now());
            }
        } catch (RuntimeException e) {
            throw new RetryableException("Settlement failed: " + e.getMessage(), e);
        }
        saga.moveTo(SagaState.SETTLEMENT_POSTED);
    }

    private void bookShipment(OrderSaga saga) {
        try {
            logistics.book(saga.getOrderId());
        } catch (RuntimeException e) {
            throw new RetryableException("Shipment booking failed: " + e.getMessage(), e);
        }
        saga.moveTo(SagaState.SHIPMENT_BOOKED);
    }

    private Instant backoff(int attempts) {
        long seconds = Math.min(300, (long) Math.pow(2, attempts)); // capped exponential
        return Instant.now().plus(Duration.ofSeconds(seconds));
    }
}
