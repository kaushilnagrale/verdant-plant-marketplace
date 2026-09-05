package com.verdant.payment;

import com.razorpay.Order;
import com.razorpay.Payment;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.razorpay.Refund;
import com.verdant.common.domain.Money;
import org.json.JSONObject;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Real Razorpay integration, active everywhere except the "test" profile
 * (see StubPaymentGateway) - the automated test suite must never depend on
 * a live network call to an external payment provider.
 */
@Component
@Profile("!test")
public class RazorpayGateway implements PaymentGateway {

    private final RazorpayClient client;

    public RazorpayGateway(RazorpayProperties props) throws RazorpayException {
        this.client = new RazorpayClient(props.keyId(), props.keySecret());
    }

    @Override
    public String createOrder(Money amount, String receiptId) {
        try {
            JSONObject request = new JSONObject();
            // Razorpay amounts are in the smallest currency unit (paise for
            // INR, i.e. amount x 100), not rupees.
            request.put("amount", amount.amount().movePointRight(2).longValueExact());
            request.put("currency", amount.currency().getCurrencyCode());
            request.put("receipt", receiptId);

            Order order = client.orders.create(request);
            return order.get("id");
        } catch (RazorpayException e) {
            throw new IllegalStateException("Razorpay order creation failed", e);
        }
    }

    @Override
    public String refund(String paymentId, Money amount) {
        try {
            JSONObject request = new JSONObject();
            request.put("amount", amount.amount().movePointRight(2).longValueExact());
            request.put("speed", "normal");

            Refund refund = client.payments.refund(paymentId, request);
            return refund.get("id");
        } catch (RazorpayException e) {
            throw new IllegalStateException("Razorpay refund failed", e);
        }
    }

    @Override
    public List<ProviderPayment> paymentsCapturedOn(LocalDate date) {
        // Razorpay's from/to are inclusive Unix-epoch-second boundaries
        // filtered against the payment's created_at - computed from the
        // IST calendar day, not the JVM/DB default zone, since "yesterday"
        // means IST here (ReconciliationJob runs on an IST cron).
        ZoneId ist = ZoneId.of("Asia/Kolkata");
        long from = date.atStartOfDay(ist).toEpochSecond();
        long to = date.plusDays(1).atStartOfDay(ist).toEpochSecond() - 1;

        List<ProviderPayment> results = new ArrayList<>();
        int skip = 0;
        final int pageSize = 100; // Razorpay's own documented max per page.
        try {
            while (true) {
                JSONObject query = new JSONObject();
                query.put("from", from);
                query.put("to", to);
                query.put("count", pageSize);
                query.put("skip", skip);

                List<Payment> page = client.payments.fetchAll(query);
                for (Payment p : page) {
                    // Only a capture is money actually taken - "created"/
                    // "authorized"/"failed" payments never became real
                    // revenue and have nothing to reconcile against an
                    // order we'd have marked PAID.
                    String status = p.get("status");
                    if (!"captured".equals(status)) {
                        continue;
                    }
                    long paise = ((Number) p.get("amount")).longValue();
                    long createdAtEpoch = ((Number) p.get("created_at")).longValue();
                    results.add(new ProviderPayment(
                        p.get("id"),
                        p.get("order_id"),
                        Money.of(BigDecimal.valueOf(paise, 2)),
                        status,
                        Instant.ofEpochSecond(createdAtEpoch)));
                }
                // Fewer than a full page means this was the last page - a
                // day with exactly a multiple of pageSize would cost one
                // harmless extra empty-page round trip, never lose data
                // (the opposite mistake - stopping at "page not empty" -
                // would silently truncate a busier day).
                if (page.size() < pageSize) {
                    break;
                }
                skip += pageSize;
            }
            return results;
        } catch (RazorpayException e) {
            throw new IllegalStateException("Fetching Razorpay payments failed", e);
        }
    }
}
