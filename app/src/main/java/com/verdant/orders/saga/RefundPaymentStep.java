package com.verdant.orders.saga;

import com.verdant.common.domain.Money;
import com.verdant.payment.PaymentGateway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

@Component
public class RefundPaymentStep implements CompensationStep {

    private final JdbcTemplate jdbc;
    private final PaymentGateway paymentGateway;

    public RefundPaymentStep(JdbcTemplate jdbc, PaymentGateway paymentGateway) {
        this.jdbc = jdbc;
        this.paymentGateway = paymentGateway;
    }

    @Override
    public String name() {
        return "REFUND_PAYMENT";
    }

    @Override
    public void execute(OrderSaga saga) {
        Map<String, Object> order = jdbc.queryForMap(
            "SELECT razorpay_payment_id, total_amount FROM orders WHERE id = ?", saga.getOrderId());
        String paymentId = (String) order.get("razorpay_payment_id");
        if (paymentId == null) {
            return; // never actually paid (failure happened before PAYMENT_CAPTURED) - nothing to refund
        }
        Money amount = Money.of((BigDecimal) order.get("total_amount"));
        paymentGateway.refund(paymentId, amount);
    }
}
