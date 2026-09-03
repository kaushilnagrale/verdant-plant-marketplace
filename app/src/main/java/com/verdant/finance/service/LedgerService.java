package com.verdant.finance.service;

import com.verdant.common.domain.Money;
import com.verdant.finance.domain.BalanceSnapshot;
import com.verdant.finance.domain.LedgerEntry;
import com.verdant.finance.domain.LedgerTransaction;
import com.verdant.finance.repository.BalanceSnapshotRepository;
import com.verdant.finance.repository.LedgerEntryRepository;
import com.verdant.finance.repository.LedgerTransactionRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

@Service
public class LedgerService {

    private final LedgerTransactionRepository transactions;
    private final LedgerEntryRepository entries;
    private final BalanceSnapshotRepository snapshots;
    private final TransactionTemplate isolatedInsert;

    public LedgerService(LedgerTransactionRepository transactions, LedgerEntryRepository entries,
                          BalanceSnapshotRepository snapshots, PlatformTransactionManager txManager) {
        this.transactions = transactions;
        this.entries = entries;
        this.snapshots = snapshots;
        this.isolatedInsert = new TransactionTemplate(txManager);
        this.isolatedInsert.setPropagationBehavior(DefaultTransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Posts a balanced transaction. Idempotent on idempotencyKey: a replay
     * returns the original transaction without posting again.
     */
    @Transactional
    public LedgerTransaction post(String idempotencyKey, String kind, String reference, List<EntrySpec> specs) {
        if (specs.size() < 2) {
            throw new IllegalArgumentException("A transaction needs at least two legs");
        }

        Money sum = specs.stream().map(EntrySpec::amount).reduce(Money.ZERO, Money::plus);
        if (!sum.isZero()) {
            throw new UnbalancedTransactionException(sum);
        }

        try {
            // A genuinely separate physical transaction (its own connection),
            // not a savepoint: Spring's JpaTransactionManager does not support
            // real savepoints for JPA transactions (Hibernate's in-memory
            // entity cache can't be safely rolled back to match a partial
            // savepoint rollback - PROPAGATION_NESTED fails here with
            // "JpaDialect does not support savepoints", a real Spring/JPA
            // limitation, not a config oversight). REQUIRES_NEW sidesteps it:
            // if this insert violates the unique idempotency-key constraint,
            // only THIS isolated transaction aborts. The outer transaction
            // was never touched, so the catch block's lookup below runs
            // cleanly - without this, Postgres marks the WHOLE surrounding
            // transaction aborted on any error, and even that harmless
            // lookup would fail with "current transaction is aborted."
            return isolatedInsert.execute(status -> {
                LedgerTransaction txn = transactions.saveAndFlush(
                    new LedgerTransaction(idempotencyKey, kind, reference));
                specs.forEach(s -> entries.save(new LedgerEntry(txn.getId(), s.accountId(), s.amount())));
                entries.flush();
                return txn;
            });

        } catch (DataIntegrityViolationException e) {
            return transactions.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> e); // genuine violation, not a replay
        }
    }

    /** Corrections are new reversing transactions. Nothing is ever edited. */
    @Transactional
    public LedgerTransaction reverse(UUID originalId, String idempotencyKey, String reason) {
        LedgerTransaction original = transactions.findById(originalId).orElseThrow();

        List<EntrySpec> reversed = entries.findByTransactionId(originalId).stream()
            .map(e -> new EntrySpec(e.getAccountId(), e.amount().negate()))
            .toList();

        LedgerTransaction txn = post(idempotencyKey, "REVERSAL", reason, reversed);
        // txn was persisted via post()'s own isolated (REQUIRES_NEW)
        // transaction, so it is detached from this method's persistence
        // context - mutating it in memory alone would never be flushed.
        // save() re-attaches it (a merge/update, since it already has an id).
        txn.markReverses(original.getId());
        return transactions.save(txn);
    }

    /** Fast path: latest snapshot plus entries after it. */
    @Transactional(readOnly = true)
    public Money balance(UUID accountId) {
        return snapshots.latestFor(accountId)
            .map(s -> s.runningBalance().plus(Money.of(entries.sumAfter(accountId, s.asOfEntryId()))))
            .orElseGet(() -> Money.of(entries.sumAll(accountId)));
    }

    /** Slow path: sums every entry from scratch, ignoring any snapshot. Used only by tests and reconciliation. */
    @Transactional(readOnly = true)
    public Money balanceViaFullReplay(UUID accountId) {
        return entries.findByAccountIdOrderById(accountId).stream()
            .map(LedgerEntry::amount)
            .reduce(Money.ZERO, Money::plus);
    }

    @Transactional
    public void snapshot(UUID accountId) {
        Long maxEntryId = entries.maxEntryIdFor(accountId);
        if (maxEntryId == null) {
            return; // no entries yet - nothing to snapshot
        }
        Money balance = balance(accountId);
        snapshots.save(new BalanceSnapshot(accountId, maxEntryId, balance));
    }

    public record EntrySpec(UUID accountId, Money amount) { }
}
