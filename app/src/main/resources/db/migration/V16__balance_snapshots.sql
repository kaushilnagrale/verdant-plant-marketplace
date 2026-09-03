CREATE TABLE balance_snapshots (
    account_id      UUID   NOT NULL REFERENCES ledger_accounts(id),
    as_of_entry_id  BIGINT NOT NULL,
    running_balance NUMERIC(14,2) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, as_of_entry_id)
);

CREATE INDEX ON balance_snapshots (account_id, as_of_entry_id DESC);
