package com.verdant.finance.domain;

import com.verdant.common.domain.Money;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

/** Immutable by design (see the no-update/no-delete rules in V13) - a correction is a new reversing entry, never an edit. */
@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency = "INR";

    protected LedgerEntry() { } // required by JPA

    public LedgerEntry(UUID transactionId, UUID accountId, Money amount) {
        this.transactionId = transactionId;
        this.accountId = accountId;
        this.amount = amount.amount();
        this.currency = amount.currency().getCurrencyCode();
    }

    public Long getId() { return id; }
    public UUID getTransactionId() { return transactionId; }
    public UUID getAccountId() { return accountId; }
    public Money amount() { return new Money(amount, Currency.getInstance(currency)); }
}
