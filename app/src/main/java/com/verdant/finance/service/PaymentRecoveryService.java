package com.verdant.finance.service;

import com.verdant.checkout.PaymentVerificationService;
import com.verdant.payment.ProviderPayment;
import org.springframework.stereotype.Service;

/**
 * What makes reconciliation self-healing rather than just a report (Day
 * 46): a payment Razorpay captured but we never recorded gets replayed
 * through the exact same guarded, idempotent core the real webhook uses
 * (markPaidByRazorpayOrderId's atomic `WHERE status = 'CREATED'` update),
 * so a payment that was actually already processed by the time this runs
 * - the real webhook simply arrived late, in the gap between fetching
 * Razorpay's report and this replay executing - is a safe no-op, never a
 * double-settle.
 */
@Service
public class PaymentRecoveryService {

    private final PaymentVerificationService paymentVerification;

    public PaymentRecoveryService(PaymentVerificationService paymentVerification) {
        this.paymentVerification = paymentVerification;
    }

    /** Returns true only if this call is the one that actually marked the order paid. */
    public boolean replayPayment(ProviderPayment payment) {
        if (payment.orderId() == null) {
            // Can't happen for a payment created through our own checkout
            // (every createOrder() call sets a receipt/order id) - guards
            // against a stray/manual Razorpay payment with no order link
            // rather than crashing the whole reconciliation run over it.
            return false;
        }
        return paymentVerification.markPaidByRazorpayOrderId(payment.orderId(), payment.paymentId());
    }
}
