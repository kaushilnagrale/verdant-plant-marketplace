-- Service extraction (63.1): a real Postgres access boundary between a
-- "catalog" domain and a "finance" domain, enforced where it actually
-- matters - GRANT/REVOKE at the database, not just a Java-level package
-- convention nothing stops a future controller from violating. See
-- DECISIONS.md for the real scope call behind this migration: a full
-- physical schema move (ALTER TABLE ... SET SCHEMA) for ~20 tables this
-- late in an already-large, working, extensively-tested monolith would
-- touch every unqualified query across the whole codebase for a
-- learning exercise's own sake - real risk for no real benefit this
-- session can responsibly take on. What's built for real instead: two
-- real, least-privilege Postgres roles, applied to the SAME tables in
-- place, plus a genuinely separate, independently-runnable
-- catalog-service module (Gradle subproject) that connects as one of
-- them - the actual mechanism a physical split would rely on, proven
-- against real Postgres, without gambling the working system on the
-- full cutover.
--
-- LOGIN password here is a real, working dev/test credential, not a
-- placeholder string - deliberately, the same way this project's own
-- docker-compose Postgres credentials are real-but-dev. A real
-- deployment would ALTER ROLE ... PASSWORD from a secrets manager post-
-- deploy and never carry a live credential in a tracked migration file.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'catalog_service_role') THEN
        CREATE ROLE catalog_service_role LOGIN PASSWORD 'catalog-service-dev-password';
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'finance_service_role') THEN
        CREATE ROLE finance_service_role LOGIN PASSWORD 'finance-service-dev-password';
    END IF;
END $$;

GRANT USAGE ON SCHEMA public TO catalog_service_role, finance_service_role;

-- Deny-by-default baseline, explicit rather than assumed: a fresh role
-- already starts with no privileges, but revoking everything first
-- means this migration's own intent (least privilege) holds even if
-- something upstream (a future default-privileges change, a manual
-- grant someone runs by hand later) ever gave these roles more.
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM catalog_service_role, finance_service_role;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM catalog_service_role, finance_service_role;

-- catalog_service_role: product discovery and the assistant features
-- built on top of it (Weeks 3, 9, 11) - what a real catalog-service
-- would own.
GRANT SELECT, INSERT, UPDATE, DELETE ON
    canonical_products, listings, listing_images, inventory, inventory_adjustments,
    mapping_decisions, search_projection_state, search_reindex_queue,
    knowledge_documents, knowledge_chunks, assistant_interactions, festival_calendar
    TO catalog_service_role;
GRANT USAGE ON
    inventory_adjustments_id_seq, mapping_decisions_id_seq, festival_calendar_id_seq
    TO catalog_service_role;

-- finance_service_role: the ledger, payouts, and reconciliation (Weeks
-- 5, 8) - what a real finance-service would own.
GRANT SELECT, INSERT, UPDATE, DELETE ON
    ledger_accounts, ledger_entries, ledger_transactions, payout_cycles, payout_line_items,
    balance_snapshots, reconciliation_runs, reconciliation_breaks, cod_remittances, bank_verifications
    TO finance_service_role;
GRANT USAGE ON
    ledger_entries_id_seq, payout_line_items_id_seq
    TO finance_service_role;

-- Deliberately NOT granted to either role: orders/sellers/logistics/
-- quality/notifications and everything else stay with the core
-- monolith for now (DECISIONS.md) - this migration only carves out the
-- two domains actually being extracted this week.
