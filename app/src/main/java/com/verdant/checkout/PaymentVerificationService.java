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

import java.util.UUID;

/**
 * The buyer's client hands back razorpay_order_id/payment_id/signature after
 * paying - none of that is trustworthy on its own (anyone can POST made-up
 * values). The signature is an HMAC over the two ids, computed with our Key
 * Secret, which only Razorpay and we know - so a signature that checks out
 * is proof Razorpay actually issued it, not a claim we're taking on faith.
 */
@Service
public class PaymentVerificationService {

    private final RazorpayProperties razorpayProps;
    private final JdbcTemplate jdbc;

    public PaymentVerificationService(RazorpayProperties razorpayProps, JdbcTemplate jdbc) {
        this.razorpayProps = razorpayProps;
        this.jdbc = jdbc;
    }

    /**
     * Marking the order PAID and marking its reservations fulfilled happen in
     * one transaction on purpose: a crash between the two would leave a paid
     * order whose stock the expiry sweep could still wrongly release back
     * onto the shelf (the exact gap fulfilled_at exists to close).
     */
    @Transactional
    public void verifyPayment(UUID orderId, PaymentVerificationRequest request) {
        if (!signatureIsValid(request)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid payment signature");
        }

        // Guarded, atomic UPDATE: only an order still in CREATED state gets
        // marked PAID here. A retried/duplicate verification call for an
        // already-processed order sees 0 rows and does nothing further -
        // idempotent by construction, same shape as reservation release.
        int rowsUpdated = jdbc.update("""
            UPDATE orders
               SET status = 'PAID', razorpay_payment_id = ?, paid_at = now()
             WHERE id = ? AND status = 'CREATED' AND razorpay_order_id = ?
            """, request.razorpayPaymentId(), orderId, request.razorpayOrderId());

        if (rowsUpdated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Order is not payable (already processed, or order id mismatch)");
        }

        jdbc.update("""
            UPDATE reservations
               SET fulfilled_at = now()
             WHERE order_id = ? AND released_at IS NULL AND fulfilled_at IS NULL
            """, orderId);
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
