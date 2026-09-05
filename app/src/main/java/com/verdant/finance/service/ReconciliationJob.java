package com.verdant.finance.service;

import com.verdant.common.alerting.Alerting;
import com.verdant.finance.domain.BreakType;
import com.verdant.payment.PaymentGateway;
import com.verdant.payment.ProviderPayment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Your webhook endpoint will be down at some point. A payment will succeed
 * at Razorpay and never reach you. This is how you find out the same day
 * rather than when a customer complains.
 *
 * Deliberately NOT one big @Transactional method, unlike the runbook's own
 * sketch: paymentsCapturedOn() is a real external HTTP call (possibly
 * several, paginated), and holding a DB transaction open across a network
 * call to a third party is exactly the anti-pattern CheckoutService (Day
 * 23) was built to avoid - a slow or hanging Razorpay response would pin a
 * pooled connection the entire time for no reason. The provider fetch runs
 * first, with no transaction; each DB-side step after that is its own
 * small, already-atomic unit (ReconciliationRunRepository.start()/
 * complete(), each break insert, PaymentRecoveryService's own guarded
 * update) - correct without needing one big transaction to wrap them.
 */
@Component
public class ReconciliationJob {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final PaymentGateway razorpay;
    private final ReconciliationRunRepository runs;
    private final ReconciliationBreakRepository breaks;
    private final PaymentRecoveryService recovery;
    private final Alerting alerting;
    private final JdbcTemplate jdbc;

    public ReconciliationJob(PaymentGateway razorpay, ReconciliationRunRepository runs,
                              ReconciliationBreakRepository breaks, PaymentRecoveryService recovery,
                              Alerting alerting, JdbcTemplate jdbc) {
        this.razorpay = razorpay;
        this.runs = runs;
        this.breaks = breaks;
        this.recovery = recovery;
        this.alerting = alerting;
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "${verdant.reconciliation.cron:0 0 4 * * *}")
    public void reconcileYesterday() {
        reconcile(LocalDate.now(IST).minusDays(1));
    }

    /**
     * Exposed as a plain method taking an explicit date - not just the
     * @Scheduled entry point - so a test can reconcile a specific,
     * fully-controlled fixture date instead of depending on the real
     * wall-clock "yesterday" (the same reasoning DispatchOrchestrationJob's
     * orchestrate() and HoldReleaseJob's review() are directly callable,
     * Week 7).
     */
    public void reconcile(LocalDate date) {
        UUID runId = runs.start(date);
        try {
            List<ProviderPayment> provider = razorpay.paymentsCapturedOn(date);
            Map<String, InternalOrder> internal = internalPaymentsFor(date);
            // Captured before the loop below starts remove()-ing matched
            // entries out of the map - internal.size() afterward only
            // counts leftovers (MISSING_PROVIDER candidates), which would
            // silently undercount the true total by exactly however many
            // orders hit AMOUNT_MISMATCH/STATUS_MISMATCH (removed from the
            // map, but not added to `matched` either).
            int internalCount = internal.size();

            int matched = 0;
            for (ProviderPayment p : provider) {
                InternalOrder mine = internal.remove(p.paymentId());

                if (mine == null) {
                    // Provider says captured, we never saw it - usually a
                    // missed webhook. Self-healing: replay it through the
                    // same idempotent path the real webhook uses.
                    breaks.record(runId, BreakType.MISSING_INTERNAL, p.paymentId(), null,
                        p.amount().amount(), null);
                    recovery.replayPayment(p);
                } else if (mine.totalAmount().compareTo(p.amount().amount()) != 0) {
                    breaks.record(runId, BreakType.AMOUNT_MISMATCH, p.paymentId(), mine.orderId(),
                        p.amount().amount(), mine.totalAmount());
                } else if (!"PAID".equals(mine.status())) {
                    // Kept as real defense in depth even though nothing in
                    // this codebase's normal flow can currently produce it
                    // (razorpay_payment_id is only ever set atomically
                    // together with status='PAID') - the same reasoning
                    // ListingController keeps an ownership check RLS now
                    // makes unreachable for the cross-tenant case (Day 45).
                    breaks.record(runId, BreakType.STATUS_MISMATCH, p.paymentId(), mine.orderId(),
                        p.amount().amount(), mine.totalAmount());
                } else {
                    matched++;
                }
            }

            // Anything left in `internal` exists on our side but Razorpay's
            // captured list for this day never returned it. Note: a
            // payment that was captured and later genuinely refunded also
            // lands here, since a refunded payment no longer shows as
            // "captured" on Razorpay's side either - refund reconciliation
            // is a distinct, more complex problem this day deliberately
            // doesn't attempt to solve.
            for (InternalOrder mine : internal.values()) {
                breaks.record(runId, BreakType.MISSING_PROVIDER, mine.razorpayPaymentId(), mine.orderId(),
                    null, mine.totalAmount());
            }

            int breaksFound = breaks.countUnresolvedForRun(runId);
            runs.complete(runId, provider.size(), internalCount, matched, breaksFound);

            if (breaksFound > 0) {
                alerting.raise("Reconciliation " + date + ": " + breaksFound + " break(s) found");
            }
        } catch (RuntimeException e) {
            runs.markFailed(runId);
            throw e;
        }
    }

    /**
     * No dedicated Order entity/repository exists anywhere in this
     * codebase (PaymentVerificationService/OrderExpiryService both query
     * `orders` directly too) - matching that established convention rather
     * than inventing one here for a single query used by nothing else.
     *
     * Scoped to razorpay_payment_id IS NOT NULL (a payment was recorded
     * against this order at all) rather than status = 'PAID' specifically,
     * so an order that drifted away from PAID after the fact can still
     * surface via the status-mismatch check above instead of silently
     * vanishing from the internal side entirely. paid_at is the date
     * anchor since it is set once, atomically with the payment id, and
     * never cleared afterward.
     */
    private Map<String, InternalOrder> internalPaymentsFor(LocalDate date) {
        Instant from = date.atStartOfDay(IST).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(IST).toInstant();

        List<InternalOrder> rows = jdbc.query("""
            SELECT id, razorpay_order_id, razorpay_payment_id, status, total_amount
              FROM orders
             WHERE razorpay_payment_id IS NOT NULL AND paid_at >= ? AND paid_at < ?
            """, (rs, n) -> new InternalOrder(
                (UUID) rs.getObject("id"),
                rs.getString("razorpay_order_id"),
                rs.getString("razorpay_payment_id"),
                rs.getString("status"),
                rs.getBigDecimal("total_amount")),
            Timestamp.from(from), Timestamp.from(to));

        Map<String, InternalOrder> byPaymentId = new HashMap<>();
        for (InternalOrder row : rows) {
            byPaymentId.put(row.razorpayPaymentId(), row);
        }
        return byPaymentId;
    }

    private record InternalOrder(UUID orderId, String razorpayOrderId, String razorpayPaymentId,
                                  String status, BigDecimal totalAmount) {
    }
}
