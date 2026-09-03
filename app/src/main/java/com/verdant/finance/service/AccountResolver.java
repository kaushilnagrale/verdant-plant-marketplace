package com.verdant.finance.service;

import com.verdant.finance.domain.AccountType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Not defined in the runbook - LedgerService.EntrySpec needs a ledger
 * account UUID, but nothing else creates/looks one up. Accounts are
 * created lazily on first use (get-or-create), matching the singleton
 * partial-unique-index design from V13 for platform accounts.
 *
 * REQUIRES_NEW here on purpose: a caller like SettlementService resolves
 * an account and then calls LedgerService.post(), which itself runs its
 * insert in an isolated REQUIRES_NEW transaction (a separate physical
 * connection - see LedgerService). If account resolution just joined the
 * caller's own still-open transaction, that new account row would not be
 * committed yet, and post()'s separate connection would see it as not
 * existing at all - a real foreign key violation this caught, not
 * theoretical. Resolving (and committing) the account independently first
 * guarantees it is durably visible before post() ever runs.
 */
@Service
public class AccountResolver {

    private final JdbcTemplate jdbc;

    public AccountResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID buyerReceivable(UUID buyerId) {
        return resolvePerOwner(AccountType.BUYER_RECEIVABLE, buyerId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID sellerPayable(UUID sellerId) {
        return resolvePerOwner(AccountType.SELLER_PAYABLE, sellerId);
    }

    /** Singleton platform accounts (owner_ref NULL) - e.g. COMMISSION_REVENUE, PLATFORM_CASH. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID platform(AccountType type) {
        jdbc.update("""
            INSERT INTO ledger_accounts (type, owner_ref) VALUES (?, NULL)
            ON CONFLICT (type, currency) WHERE owner_ref IS NULL DO NOTHING
            """, type.name());
        return jdbc.queryForObject(
            "SELECT id FROM ledger_accounts WHERE type = ? AND owner_ref IS NULL", UUID.class, type.name());
    }

    private UUID resolvePerOwner(AccountType type, UUID ownerRef) {
        jdbc.update("""
            INSERT INTO ledger_accounts (type, owner_ref) VALUES (?, ?)
            ON CONFLICT (type, owner_ref, currency) DO NOTHING
            """, type.name(), ownerRef);
        return jdbc.queryForObject(
            "SELECT id FROM ledger_accounts WHERE type = ? AND owner_ref = ?", UUID.class, type.name(), ownerRef);
    }
}
