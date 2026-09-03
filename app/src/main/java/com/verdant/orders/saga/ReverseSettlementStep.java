package com.verdant.orders.saga;

import com.verdant.finance.service.LedgerService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class ReverseSettlementStep implements CompensationStep {

    private final JdbcTemplate jdbc;
    private final LedgerService ledger;

    public ReverseSettlementStep(JdbcTemplate jdbc, LedgerService ledger) {
        this.jdbc = jdbc;
        this.ledger = ledger;
    }

    @Override
    public String name() {
        return "REVERSE_SETTLEMENT";
    }

    /**
     * An order can now settle once per seller (Day 32/33 fix - see
     * SettlementService's idempotency key). Reverses every settlement
     * transaction posted for this order, each under its own deterministic
     * idempotency key, so re-running this step after a partial failure
     * (some sellers reversed, not others) never double-reverses one that
     * already succeeded - LedgerService.post()'s own idempotent-replay
     * guard handles that per reversal.
     */
    @Override
    public void execute(OrderSaga saga) {
        List<UUID> settlementTxnIds = jdbc.queryForList("""
            SELECT id FROM ledger_transactions WHERE reference = ? AND kind = 'ORDER_SETTLEMENT'
            """, UUID.class, saga.getOrderId().toString());

        for (UUID txnId : settlementTxnIds) {
            ledger.reverse(txnId, "settle-reversal:" + txnId, "Order " + saga.getOrderId() + " compensation");
        }
    }
}
