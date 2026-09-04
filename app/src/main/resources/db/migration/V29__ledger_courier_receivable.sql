-- Not in the runbook - adding COURIER_RECEIVABLE to AccountType (needed
-- for ClaimResolutionService's courier-fault path, since the runbook
-- itself never defines debitCourierRecoverable) also needs a matching
-- migration here: ledger_accounts.type has its own DB-level CHECK
-- constraint independent of the Java enum, and Postgres rejected the
-- first real attempt to post a COURIER_RECEIVABLE account with a genuine
-- constraint violation, not a hypothetical one - same lesson as Day 38's
-- shipments status CHECK.
ALTER TABLE ledger_accounts DROP CONSTRAINT ledger_accounts_type_check;
ALTER TABLE ledger_accounts ADD CONSTRAINT ledger_accounts_type_check CHECK (type IN
    ('BUYER_RECEIVABLE','SELLER_PAYABLE','COMMISSION_REVENUE','TAX_WITHHELD',
     'GATEWAY_FEE_EXPENSE','PLATFORM_CASH','CLAIMS_EXPENSE','REFUNDS_PAYABLE',
     'COURIER_RECEIVABLE'));
