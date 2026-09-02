package com.verdant.checkout;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OrderExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(OrderExpirySweeper.class);

    private final OrderExpiryService orderExpiry;

    public OrderExpirySweeper(OrderExpiryService orderExpiry) {
        this.orderExpiry = orderExpiry;
    }

    @Scheduled(fixedRate = 60_000)
    public void sweep() {
        int expired = orderExpiry.expireOrders();
        if (expired > 0) {
            log.info("Expired {} unpaid order(s)", expired);
        }
    }
}
