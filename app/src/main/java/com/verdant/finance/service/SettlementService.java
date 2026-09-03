package com.verdant.finance.service;

import com.verdant.common.domain.Money;
import com.verdant.finance.domain.AccountType;
import com.verdant.finance.domain.LedgerTransaction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class SettlementService {

    private final LedgerService ledger;
    private final AccountResolver accounts;
    private final RateConfigService rates;

    public SettlementService(LedgerService ledger, AccountResolver accounts, RateConfigService rates) {
        this.ledger = ledger;
        this.accounts = accounts;
        this.rates = rates;
    }

    @Transactional
    public LedgerTransaction settle(UUID orderId, UUID buyerId, UUID sellerId, Money gross, LocalDate asOf) {
        Money commission = gross.multiply(rates.rate("COMMISSION", sellerId, asOf));
        Money tcs = gross.multiply(rates.rate("TCS", null, asOf));
        Money gatewayFee = gross.multiply(rates.rate("GATEWAY_FEE", null, asOf));
        // Derived by SUBTRACTION, never as its own percentage. Four
        // independent percentages of one amount will not generally sum back
        // to that amount after rounding - the residual lands here instead,
        // so the transaction balances by construction, not by luck.
        Money payable = gross.minus(commission).minus(tcs).minus(gatewayFee);

        // ledger_entries forbids a zero-amount row (a zero entry carries no
        // information, V13) - and at a small enough gross, a percentage leg
        // can legitimately round to exactly 0.00 (e.g. 1% of one paisa).
        // Dropping a zero leg never breaks the balance, since it contributed
        // nothing to the sum in the first place.
        List<LedgerService.EntrySpec> legs = List.of(
                new LedgerService.EntrySpec(accounts.buyerReceivable(buyerId), gross.negate()),
                new LedgerService.EntrySpec(accounts.sellerPayable(sellerId), payable),
                new LedgerService.EntrySpec(accounts.platform(AccountType.COMMISSION_REVENUE), commission),
                new LedgerService.EntrySpec(accounts.platform(AccountType.TAX_WITHHELD), tcs),
                new LedgerService.EntrySpec(accounts.platform(AccountType.GATEWAY_FEE_EXPENSE), gatewayFee)
            ).stream()
            .filter(spec -> !spec.amount().isZero())
            .toList();

        return ledger.post(
            "settle:" + orderId, // idempotency derived from the order - a retried settlement never double-posts
            "ORDER_SETTLEMENT",
            orderId.toString(),
            legs);
    }
}
