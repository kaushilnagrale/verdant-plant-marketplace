package com.verdant.checkout;

import com.razorpay.RazorpayException;
import com.razorpay.Utils;
import com.verdant.payment.RazorpayProperties;
import org.json.JSONObject;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Two independent paths can report "payment succeeded": the buyer's client
 * calling back after Razorpay's checkout widget closes (verifyPayment), and
 * Razorpay's own webhook calling our server directly (markPaidByRazorpayOrderId)
 * as a backup for when the client never calls back at all (tab closed,
 * app crashed). Both converge on the same guarded, idempotent core so a
 * given payment is only ever processed once, however it's reported.
 */
@Service
public class PaymentVerificationService {

    private final RazorpayProperties razorpayProps;
    private final JdbcTemplate jdbc;

    public PaymentVerificationService(RazorpayProperties razorpayProps, JdbcTemplate jdbc) {
        this.razorpayProps = razorpayProps;
        this.jdbc = jdbc;
    }

    /** Client-callback path: proof is the checkout signature (HMAC of order_id|payment_id with our Key Secret). */
    @Transactional
    public void verifyPayment(UUID orderId, PaymentVerificationRequest request) {
        if (!signatureIsValid(request)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid payment signature");
        }

        boolean marked = markPaidAndFulfill(orderId, request.razorpayOrderId(), request.razorpayPaymentId());
        if (!marked) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Order is not payable (already processed, or order id mismatch)");
        }
    }

    /**
     * Webhook path: the caller (RazorpayWebhookController) has already
     * verified the webhook signature over the raw request body before this
     * is called, so razorpay_order_id itself is trusted here - we look up
     * our order by it directly rather than requiring a path orderId.
     * Returns false (not an error) for an unknown/already-processed order,
     * since a webhook retry for something we've already handled is normal.
     */
    @Transactional
    public boolean markPaidByRazorpayOrderId(String razorpayOrderId, String razorpayPaymentId) {
        List<UUID> matches = jdbc.queryForList(
            "SELECT id FROM orders WHERE razorpay_order_id = ?", UUID.class, razorpayOrderId);
        if (matches.isEmpty()) {
            return false;
        }
        return markPaidAndFulfill(matches.get(0), razorpayOrderId, razorpayPaymentId);
    }

    /**
     * Marking the order PAID and marking its reservations fulfilled happen in
     * one transaction on purpose: a crash between the two would leave a paid
     * order whose stock the expiry sweep could still wrongly release back
     * onto the shelf (the exact gap fulfilled_at exists to close).
     *
     * The guarded, atomic UPDATE (WHERE status = 'CREATED') means a retried
     * or duplicate call - from either path, or both paths firing for the
     * same payment - only ever processes the order once. 0 rows = already
     * handled, nothing more to do.
     */
    private boolean markPaidAndFulfill(UUID orderId, String razorpayOrderId, String razorpayPaymentId) {
        int rowsUpdated = jdbc.update("""
            UPDATE orders
               SET status = 'PAID', razorpay_payment_id = ?, paid_at = now()
             WHERE id = ? AND status = 'CREATED' AND razorpay_order_id = ?
            """, razorpayPaymentId, orderId, razorpayOrderId);

        if (rowsUpdated == 0) {
            return false;
        }

        jdbc.update("""
            UPDATE reservations
               SET fulfilled_at = now()
             WHERE order_id = ? AND released_at IS NULL AND fulfilled_at IS NULL
            """, orderId);
        return true;
    }

    private boolean signatureIsValid(PaymentVerificationRequest request) {
        JSONObject options = new JSONObject();
        options.put("razorpay_order_id", request.razorpayOrderId());
        options.put("razorpay_payment_id", request.razorpayPaymentId());
        options.put("razorpay_signature", request.razorpaySignature());
        try {
            return Utils.verifyPaymentSignature(options, razorpayProps.keySecret());
        } catch (RazorpayException e) {
            return false;
        }
    }
}
