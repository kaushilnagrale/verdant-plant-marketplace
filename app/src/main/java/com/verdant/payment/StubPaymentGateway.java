package com.verdant.payment;

import com.verdant.common.domain.Money;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Profile("test")
public class StubPaymentGateway implements PaymentGateway {
    @Override
    public String createOrder(Money amount, String receiptId) {
        return "order_stub_" + UUID.randomUUID();
    }
}
