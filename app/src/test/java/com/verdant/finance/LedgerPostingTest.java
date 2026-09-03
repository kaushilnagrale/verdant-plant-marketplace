package com.verdant.finance;

import com.verdant.common.domain.Money;
import com.verdant.common.testing.IntegrationTestBase;
import com.verdant.finance.domain.AccountType;
import com.verdant.finance.repository.LedgerEntryRepository;
import com.verdant.finance.repository.LedgerTransactionRepository;
import com.verdant.finance.service.LedgerService;
import com.verdant.finance.service.LedgerService.EntrySpec;
import com.verdant.finance.service.UnbalancedTransactionException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.verdant.finance.domain.LedgerTransaction;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.verdant.common.domain.Money.of;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerPostingTest extends IntegrationTestBase {

    @Autowired private LedgerService ledger;
    @Autowired private LedgerTransactionRepository transactionRepo;
    @Autowired private LedgerEntryRepository entryRepo;
    @Autowired private JdbcTemplate jdbc;

    /**
     * Not defined in the runbook. Platform accounts (owner_ref NULL) are
     * singletons per V13's partial unique index, so this is an idempotent
     * get-or-create - repeated calls across test methods return the same
     * account instead of colliding on the unique constraint.
     */
    private UUID account(AccountType type) {
        jdbc.update("""
            INSERT INTO ledger_accounts (type, owner_ref) VALUES (?, NULL)
            ON CONFLICT (type, currency) WHERE owner_ref IS NULL DO NOTHING
            """, type.name());
        return jdbc.queryForObject(
            "SELECT id FROM ledger_accounts WHERE type = ? AND owner_ref IS NULL", UUID.class, type.name());
    }

    private List<EntrySpec> balancedPair(String amount) {
        UUID cash = account(AccountType.PLATFORM_CASH);
        UUID revenue = account(AccountType.COMMISSION_REVENUE);
        return List.of(
            new EntrySpec(cash, of(amount)),
            new EntrySpec(revenue, of(amount).negate()));
    }

    /**
     * Not defined in the runbook. Releases every task at once (Day 20's
     * CountDownLatch pattern), waits for all to finish, and returns each
     * task's result via Future.get() - which rethrows any exception a
     * worker thread hit instead of silently swallowing it. A version that
     * just ran tasks in a plain Runnable and ignored failures would let
     * this test pass as long as ONE of the ten calls happened to succeed,
     * without actually proving the other nine replayed gracefully.
     */
    private List<LedgerTransaction> runInParallel(int n, Callable<LedgerTransaction> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<LedgerTransaction>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();

        List<LedgerTransaction> results = new java.util.ArrayList<>();
        for (Future<LedgerTransaction> f : futures) {
            try {
                results.add(f.get());
            } catch (ExecutionException e) {
                pool.shutdown();
                throw (Exception) e.getCause();
            }
        }
        pool.shutdown();
        return results;
    }

    @Test
    void unbalancedTransaction_isRejectedBeforeAnyWrite() {
        UUID cash = account(AccountType.PLATFORM_CASH);
        UUID rev = account(AccountType.COMMISSION_REVENUE);

        long before = transactionRepo.count();

        assertThatThrownBy(() -> ledger.post("k1", "TEST", null, List.of(
                new EntrySpec(cash, of("100.00")),
                new EntrySpec(rev, of("-90.00")))))
            .isInstanceOf(UnbalancedTransactionException.class);

        assertThat(transactionRepo.count()).isEqualTo(before);
    }

    @Test
    void samePostingReplayed_postsOnce_andReturnsOriginal() {
        var first = ledger.post("k2", "TEST", null, balancedPair("100.00"));
        var second = ledger.post("k2", "TEST", null, balancedPair("100.00"));

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(entryRepo.findByTransactionId(first.getId())).hasSize(2);
    }

    @Test
    void concurrentReplays_postExactlyOnce() throws Exception {
        List<LedgerTransaction> results = runInParallel(10,
            () -> ledger.post("k3", "TEST", null, balancedPair("50.00")));

        // every one of the 10 concurrent calls must return gracefully (no
        // thread should see an exception) and all must agree on the same
        // transaction - proving replay is idempotent under real contention,
        // not just that one lucky attempt out of ten happened to work.
        assertThat(results).hasSize(10);
        UUID firstId = results.get(0).getId();
        assertThat(results).allSatisfy(txn -> assertThat(txn.getId()).isEqualTo(firstId));

        assertThat(entryRepo.findByTransactionId(firstId)).hasSize(2);
    }

    @Test
    void singleLegTransaction_isRejected() {
        assertThatThrownBy(() -> ledger.post("k4", "TEST", null,
                List.of(new EntrySpec(account(AccountType.PLATFORM_CASH), of("100.00")))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reversal_createsNewEntries_andLeavesOriginalIntact() {
        var original = ledger.post("k5", "TEST", null, balancedPair("100.00"));
        var reversal = ledger.reverse(original.getId(), "k5-rev", "customer cancelled");

        assertThat(entryRepo.findByTransactionId(original.getId())).hasSize(2);
        assertThat(entryRepo.findByTransactionId(reversal.getId())).hasSize(2);
    }
}
