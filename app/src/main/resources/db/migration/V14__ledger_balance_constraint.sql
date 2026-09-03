CREATE OR REPLACE FUNCTION assert_transaction_balances() RETURNS TRIGGER AS $$
DECLARE
    total NUMERIC;
    leg_count INT;
BEGIN
    SELECT COALESCE(SUM(amount), 0), COUNT(*)
      INTO total, leg_count
      FROM ledger_entries
     WHERE transaction_id = NEW.transaction_id;

    IF leg_count < 2 THEN
        RAISE EXCEPTION 'Ledger transaction % has % leg(s); at least 2 required',
            NEW.transaction_id, leg_count;
    END IF;

    IF total <> 0 THEN
        RAISE EXCEPTION 'Ledger transaction % does not balance: sum = %',
            NEW.transaction_id, total;
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER entries_must_balance
AFTER INSERT ON ledger_entries
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION assert_transaction_balances();
