package com.verdant.common.tenancy;

import java.util.UUID;

/**
 * Real, previously-invisible project-wide gap found while building Week
 * 8's payout statement access control: this project's RLS policies have
 * existed since Week 1 (V2__rls.sql) and TenantIsolationTest proves the
 * POLICIES work correctly - but nothing in the actual running application
 * ever set app.current_tenant or dropped out of the connecting role's
 * privileges, so RLS was never actually enforced by a real request. The
 * root cause, confirmed empirically (not guessed): the app's datasource
 * connects as `verdant`, a genuine Postgres superuser
 * (rolsuper=t, rolbypassrls=t) - superusers unconditionally bypass ALL row
 * level security, FORCE ROW LEVEL SECURITY included, by Postgres design,
 * with no override possible. This is the missing piece: a per-request
 * context that TenantAwareDataSource reads when handing out a connection.
 *
 * Default (nothing set) is full access, not "no access" - deliberately.
 * Every @Scheduled job in this project (OutboxDrainer, OrderSagaOrchestrator,
 * ForecastRefreshJob, HoldReleaseJob, SnapshotJob...) runs on a background
 * thread with no HTTP request, no JWT, and legitimately needs to touch
 * ANY seller's data by design (a saga advances any order, the outbox
 * drains any aggregate's events). Making the default "no access" would
 * have silently broken every one of them. Only TenantContextFilter (seller-
 * authenticated HTTP requests specifically) ever narrows this down to one
 * seller for the life of a request.
 */
public final class TenantContext {

    public record Context(UUID sellerId) { }

    private static final Context FULL_ACCESS = new Context(null);
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    private TenantContext() { }

    public static void setSeller(UUID sellerId) {
        CURRENT.set(new Context(sellerId));
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static Context current() {
        Context ctx = CURRENT.get();
        return ctx != null ? ctx : FULL_ACCESS;
    }
}
