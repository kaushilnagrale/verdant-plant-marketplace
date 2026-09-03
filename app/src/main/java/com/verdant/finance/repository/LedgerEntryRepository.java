package com.verdant.finance.repository;

import com.verdant.finance.domain.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    /**
     * Not defined in the runbook. Accounts with at least `threshold` entries
     * posted since their latest snapshot (or since the beginning, if they
     * have none yet) - only these are worth re-snapshotting.
     */
    @Query(value = """
        SELECT e.account_id
          FROM ledger_entries e
          LEFT JOIN LATERAL (
            SELECT as_of_entry_id FROM balance_snapshots s
             WHERE s.account_id = e.account_id
             ORDER BY as_of_entry_id DESC LIMIT 1
          ) latest ON true
         WHERE e.id > COALESCE(latest.as_of_entry_id, 0)
         GROUP BY e.account_id
        HAVING COUNT(*) >= :threshold
        """, nativeQuery = true)
    List<UUID> accountsWithEntriesSinceLastSnapshot(@Param("threshold") int threshold);

    List<LedgerEntry> findByTransactionId(UUID transactionId);

    List<LedgerEntry> findByAccountIdOrderById(UUID accountId);

    @Query("SELECT MAX(e.id) FROM LedgerEntry e WHERE e.accountId = :accountId")
    Long maxEntryIdFor(@Param("accountId") UUID accountId);

    /** Strictly greater than asOfEntryId - an off-by-one here double counts or skips exactly one entry. */
    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM LedgerEntry e WHERE e.accountId = :accountId AND e.id > :asOfEntryId")
    BigDecimal sumAfter(@Param("accountId") UUID accountId, @Param("asOfEntryId") long asOfEntryId);

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM LedgerEntry e WHERE e.accountId = :accountId")
    BigDecimal sumAll(@Param("accountId") UUID accountId);
}
