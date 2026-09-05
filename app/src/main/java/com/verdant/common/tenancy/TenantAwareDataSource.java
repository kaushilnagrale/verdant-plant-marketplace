package com.verdant.common.tenancy;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Every connection this hands out first drops from the real, superuser
 * connecting role down to the restricted verdant_app role (which RLS
 * policies actually apply to - see TenantContext's own doc comment for
 * why the superuser connection made RLS a no-op this whole project), then
 * applies whatever TenantContext is current on this thread. The
 * connection is reset back to the superuser role and cleared of any GUC
 * before it returns to the pool - a connection must never go back to
 * HikariCP's pool carrying a dropped-privilege role or a stale tenant
 * scope that could leak into a completely unrelated later request.
 *
 * String-concatenating the seller id directly into the SET statement is
 * deliberate, not an oversight: Postgres's SET command does not accept a
 * bind parameter the way a normal query does, and sellerId is a UUID
 * object (never raw user input) whose toString() can never contain a
 * quote or any other character that would need escaping.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    public TenantAwareDataSource(DataSource targetDataSource) {
        super(targetDataSource);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(obtainTargetDataSource().getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrap(obtainTargetDataSource().getConnection(username, password));
    }

    private Connection wrap(Connection real) throws SQLException {
        applyTenantContext(real);
        return (Connection) Proxy.newProxyInstance(
            Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, new ResetOnCloseHandler(real));
    }

    private void applyTenantContext(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("SET ROLE verdant_app");
            TenantContext.Context ctx = TenantContext.current();
            if (ctx.sellerId() != null) {
                stmt.execute("SET app.current_tenant = '" + ctx.sellerId() + "'");
            } else {
                // Full access (background jobs, buyer-facing/permitAll
                // requests, platform ops) - see TenantContext's own doc
                // comment for why "no context set" means this, not "no
                // access".
                stmt.execute("SET app.is_platform_admin = 'true'");
            }
        }
    }

    private static final class ResetOnCloseHandler implements InvocationHandler {
        private final Connection real;

        ResetOnCloseHandler(Connection real) {
            this.real = real;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("close".equals(method.getName())) {
                resetBeforeReturningToPool();
            }
            try {
                return method.invoke(real, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        private void resetBeforeReturningToPool() {
            try (Statement stmt = real.createStatement()) {
                stmt.execute("RESET app.current_tenant");
                stmt.execute("RESET app.is_platform_admin");
                stmt.execute("RESET ROLE");
            } catch (SQLException e) {
                // Best-effort - if the connection is already broken enough
                // that even RESET fails, HikariCP's own validation query on
                // the next checkout will detect and discard it rather than
                // hand out a connection in an unknown state.
            }
        }
    }
}
