package com.verdant.finance;

import com.verdant.common.testing.IntegrationTestBase;
import com.verdant.finance.domain.AccountType;
import com.verdant.finance.domain.LedgerEntry;
import com.verdant.finance.domain.LedgerTransaction;
import com.verdant.finance.repository.LedgerEntryRepository;
import com.verdant.finance.service.AccountResolver;
import com.verdant.finance.service.SettlementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.verdant.common.domain.Money.of;
import static org.assertj.core.api.Assertions.assertThat;

class SettlementTest extends IntegrationTestBase {

    @Autowired private SettlementService settlement;
    @Autowired private AccountResolver accounts;
    @Autowired private LedgerEntryRepository entryRepo;
    @Autowired private JdbcTemplate jdbc;

    private UUID buyer;
    private UUID seller;

    @BeforeEach
    void freshBuyerAndSeller() {
        buyer = UUID.randomUUID();
        seller = UUID.randomUUID();
    }

    private List<LedgerEntry> entriesOf(LedgerTransaction txn) {
        return entryRepo.findByTransactionId(txn.getId());
    }

    private BigDecimal sumOf(LedgerTransaction txn) {
        return entriesOf(txn).stream()
            .map(e -> e.amount().amount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * The amount THIS transaction posted to one specific account - not the
     * account's cumulative balance. COMMISSION_REVENUE etc. are shared
     * singleton platform accounts (one row, referenced by every settlement
     * ever posted, including ones from a reused Testcontainers container's
     * earlier runs), so asserting an exact cumulative balance on them would
     * be polluted by unrelated postings. sellerPayable is scoped to a fresh
     * random seller per test so its balance IS safe to assert directly, but
     * this same per-transaction lookup works for both and is used
     * throughout for consistency.
     */
    private BigDecimal entryAmount(LedgerTransaction txn, UUID accountId) {
        return entriesOf(txn).stream()
            .filter(e -> e.getAccountId().equals(accountId))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No entry for account " + accountId + " in transaction " + txn.getId()))
            .amount().amount();
    }

    private void insertRate(String rateType, String rate, LocalDate from, LocalDate to) {
        jdbc.update("""
            INSERT INTO rate_config (rate_type, rate, effective_from, effective_to)
            VALUES (?, ?, ?, ?)
            """, rateType, new BigDecimal(rate), from, to);
    }

    @Test
    void settlement_distributesGrossAcrossAllLegs() {
        UUID order = UUID.randomUUID();
        var txn = settlement.settle(order, buyer, seller, of("1000.00"), LocalDate.now());

        assertThat(entriesOf(txn)).hasSize(5);
        assertThat(sumOf(txn)).isEqualByComparingTo("0.00");
        assertThat(entryAmount(txn, accounts.sellerPayable(seller))).isEqualByComparingTo("850.00");
        assertThat(entryAmount(txn, accounts.platform(AccountType.COMMISSION_REVENUE))).isEqualByComparingTo("120.00");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "1.00", "33.33", "99.99", "1000.00", "123456.78"})
    void settlement_balancesAtEveryAmount_despiteRounding(String gross) {
        var txn = settlement.settle(UUID.randomUUID(), buyer, seller, of(gross), LocalDate.now());
        assertThat(sumOf(txn)).isEqualByComparingTo("0.00");
    }

    @Test
    void settlementReplayed_postsOnce() {
        UUID order = UUID.randomUUID();
        var first = settlement.settle(order, buyer, seller, of("1000.00"), LocalDate.now());
        var second = settlement.settle(order, buyer, seller, of("1000.00"), LocalDate.now());

        assertThat(second.getId()).isEqualTo(first.getId());
        // seller is fresh per test, so its account was never touched before
        // this test - a plain balance check is safe here (unlike the
        // shared platform accounts elsewhere in this class).
        BigDecimal sellerBalance = jdbc.queryForObject(
            "SELECT COALESCE(SUM(amount), 0) FROM ledger_entries WHERE account_id = ?",
            BigDecimal.class, accounts.sellerPayable(seller));
        assertThat(sellerBalance).isEqualByComparingTo("850.00"); // not 1700
    }

    @Test
    void settlementDatedInThePast_usesRateEffectiveThen() {
        insertRate("COMMISSION", "0.1500", LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1));

        UUID order = UUID.randomUUID();
        var txn = settlement.settle(order, buyer, seller, of("1000.00"), LocalDate.of(2024, 6, 1));

        assertThat(entryAmount(txn, accounts.platform(AccountType.COMMISSION_REVENUE))).isEqualByComparingTo("150.00");
    }
}
