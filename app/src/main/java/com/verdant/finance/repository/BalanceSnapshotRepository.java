package com.verdant.finance.repository;

import com.verdant.common.domain.Money;
import com.verdant.finance.domain.BalanceSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Not defined in the runbook. Its key is composite (account_id +
 * as_of_entry_id) - implemented directly over JdbcTemplate rather than a
 * Spring Data JpaRepository, since a composite-key JPA entity (@IdClass or
 * @EmbeddedId) is a lot of ceremony for something this small and simple.
 */
@Repository
public class BalanceSnapshotRepository {

    private final JdbcTemplate jdbc;

    public BalanceSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<BalanceSnapshot> latestFor(UUID accountId) {
        List<BalanceSnapshot> rows = jdbc.query("""
            SELECT account_id, as_of_entry_id, running_balance
              FROM balance_snapshots
             WHERE account_id = ?
             ORDER BY as_of_entry_id DESC
             LIMIT 1
            """, (rs, rowNum) -> new BalanceSnapshot(
                (UUID) rs.getObject("account_id"),
                rs.getLong("as_of_entry_id"),
                Money.of(rs.getBigDecimal("running_balance"))),
            accountId);
        return rows.stream().findFirst();
    }

    public void save(BalanceSnapshot snapshot) {
        jdbc.update("""
            INSERT INTO balance_snapshots (account_id, as_of_entry_id, running_balance)
            VALUES (?, ?, ?)
            ON CONFLICT (account_id, as_of_entry_id)
            DO UPDATE SET running_balance = EXCLUDED.running_balance
            """, snapshot.accountId(), snapshot.asOfEntryId(), snapshot.runningBalance().amount());
    }
}
