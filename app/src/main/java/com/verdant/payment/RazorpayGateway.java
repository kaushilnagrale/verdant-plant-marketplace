package com.verdant.payment;

import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.razorpay.Refund;
import com.verdant.common.domain.Money;
import org.json.JSONObject;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

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
}
