CREATE TABLE ledger_accounts (
    id        UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    type      TEXT NOT NULL CHECK (type IN (
                'BUYER_RECEIVABLE','SELLER_PAYABLE','COMMISSION_REVENUE',
                'TAX_WITHHELD','GATEWAY_FEE_EXPENSE','PLATFORM_CASH',
                'CLAIMS_EXPENSE','REFUNDS_PAYABLE')),
    owner_ref UUID,
    currency  TEXT NOT NULL DEFAULT 'INR',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (type, owner_ref, currency)
);

-- singleton platform accounts have owner_ref NULL; the UNIQUE above allows
-- multiple NULLs in Postgres, so guard them with a partial unique index
CREATE UNIQUE INDEX ledger_accounts_singleton
    ON ledger_accounts (type, currency) WHERE owner_ref IS NULL;

CREATE TABLE ledger_transactions (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    idempotency_key TEXT NOT NULL UNIQUE,
    kind            TEXT NOT NULL,
    reference       TEXT,
    reverses_id     UUID REFERENCES ledger_transactions(id),
    posted_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ledger_entries (
    id             BIGSERIAL PRIMARY KEY,
    transaction_id UUID NOT NULL REFERENCES ledger_transactions(id),
    account_id     UUID NOT NULL REFERENCES ledger_accounts(id),
    amount         NUMERIC(14,2) NOT NULL CHECK (amount <> 0),
    currency       TEXT NOT NULL DEFAULT 'INR'
);

CREATE INDEX ON ledger_entries (account_id, id);
CREATE INDEX ON ledger_entries (transaction_id);
CREATE INDEX ON ledger_transactions (reference);

-- entries are immutable: no updates, no deletes, ever
CREATE RULE ledger_entries_no_update AS ON UPDATE TO ledger_entries DO INSTEAD NOTHING;
CREATE RULE ledger_entries_no_delete AS ON DELETE TO ledger_entries DO INSTEAD NOTHING;
