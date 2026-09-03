package com.verdant.finance;

import com.verdant.common.domain.Money;
import com.verdant.common.testing.IntegrationTestBase;
import com.verdant.finance.domain.AccountType;
import com.verdant.finance.service.LedgerService;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import net.jqwik.spring.JqwikSpringSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@JqwikSpringSupport
class LedgerSnapshotPropertyTest extends IntegrationTestBase {

    @Autowired private LedgerService ledger;
    @Autowired private JdbcTemplate jdbc;

    /** Not defined in the runbook. A fresh, isolated per-owner account (never touched by any other test). */
    private UUID freshAccount() {
        UUID owner = UUID.randomUUID();
        jdbc.update("INSERT INTO ledger_accounts (type, owner_ref) VALUES (?, ?)",
            AccountType.BUYER_RECEIVABLE.name(), owner);
        return jdbc.queryForObject(
            "SELECT id FROM ledger_accounts WHERE type = ? AND owner_ref = ?",
            UUID.class, AccountType.BUYER_RECEIVABLE.name(), owner);
    }

    /**
     * Not defined in the runbook. Posts a balanced 2-leg transaction moving
     * `paise` (100 paise = INR 1) between the two accounts. Generated values
     * can legitimately be 0, which ledger_entries forbids as an amount (see
     * Day 28) - skipped rather than posted, same reasoning as SettlementService.
     */
    private void postPair(UUID account, UUID counter, int paise) {
        Money amount = Money.of(BigDecimal.valueOf(paise, 2));
        if (amount.isZero()) {
            return;
        }
        ledger.post(UUID.randomUUID().toString(), "PROPERTY_TEST", null, List.of(
            new LedgerService.EntrySpec(account, amount),
            new LedgerService.EntrySpec(counter, amount.negate())));
    }

    @Property(tries = 2000)
    void snapshotBalance_alwaysEqualsFullReplay(
            @ForAll @Size(min = 1, max = 60) List<@IntRange(min = -500_000, max = 500_000) Integer> before,
            @ForAll @Size(min = 0, max = 60) List<@IntRange(min = -500_000, max = 500_000) Integer> after) {

        UUID account = freshAccount();
        UUID counter = freshAccount();

        before.forEach(paise -> postPair(account, counter, paise));
        ledger.snapshot(account);
        after.forEach(paise -> postPair(account, counter, paise));

        assertThat(ledger.balance(account))
            .isEqualTo(ledger.balanceViaFullReplay(account));
    }

    @Property(tries = 500)
    void multipleSnapshots_neverDrift(
            @ForAll @Size(min = 3, max = 30) List<@IntRange(min = -100_000, max = 100_000) Integer> amounts) {

        UUID account = freshAccount();
        UUID counter = freshAccount();

        for (int i = 0; i < amounts.size(); i++) {
            postPair(account, counter, amounts.get(i));
            if (i % 3 == 0) {
                ledger.snapshot(account); // snapshot mid-stream
            }
        }

        assertThat(ledger.balance(account)).isEqualTo(ledger.balanceViaFullReplay(account));
    }

    @Property(tries = 500)
    void allAccountBalances_alwaysSumToZero(
            @ForAll @Size(min = 1, max = 40) List<@IntRange(min = -100_000, max = 100_000) Integer> amounts) {

        UUID a = freshAccount();
        UUID b = freshAccount();
        amounts.forEach(paise -> postPair(a, b, paise));

        assertThat(ledger.balance(a).plus(ledger.balance(b))).isEqualTo(Money.ZERO);
    }
}
