DO $$
DECLARE
    acct_a UUID; acct_b UUID; txn UUID; leg_amount NUMERIC(14,2);
BEGIN
    INSERT INTO ledger_accounts (type, owner_ref) VALUES ('SELLER_PAYABLE', uuid_generate_v4()) RETURNING id INTO acct_a;
    INSERT INTO ledger_accounts (type, owner_ref) VALUES ('BUYER_RECEIVABLE', uuid_generate_v4()) RETURNING id INTO acct_b;

    FOR i IN 1..500000 LOOP
        INSERT INTO ledger_transactions (idempotency_key, kind)
        VALUES ('load-' || i, 'LOAD_TEST') RETURNING id INTO txn;

        -- random() * 1000 can land on exactly 0.00 across enough iterations,
        -- which ledger_entries' CHECK (amount <> 0) correctly rejects (same
        -- lesson as Day 28's settlement rounding case) - shift the range so
        -- it can never be zero.
        leg_amount := (random() * 999 + 1)::numeric(14,2);

        INSERT INTO ledger_entries (transaction_id, account_id, amount) VALUES
            (txn, acct_a,  leg_amount),
            (txn, acct_b, -leg_amount);
    END LOOP;

    RAISE NOTICE 'Account A: %', acct_a;
    RAISE NOTICE 'Account B: %', acct_b;
END $$;
