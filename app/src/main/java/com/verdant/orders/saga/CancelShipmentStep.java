package com.verdant.orders.saga;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class CancelShipmentStep implements CompensationStep {

    private final JdbcTemplate jdbc;
    private final LogisticsGateway logistics;

    public CancelShipmentStep(JdbcTemplate jdbc, LogisticsGateway logistics) {
        this.jdbc = jdbc;
        this.logistics = logistics;
    }

    @Override
    public String name() {
        return "CANCEL_SHIPMENT";
    }

    /** An order can span multiple sellers - cancels every seller's shipment for this order, not just one. */
    @Override
    public void execute(OrderSaga saga) {
        List<UUID> sellerIds = jdbc.queryForList(
            "SELECT DISTINCT seller_id FROM order_items WHERE order_id = ?", UUID.class, saga.getOrderId());
        for (UUID sellerId : sellerIds) {
            logistics.cancel(saga.getOrderId(), sellerId);
        }
    }
}
