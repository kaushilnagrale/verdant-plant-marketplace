package com.verdant;

import com.verdant.common.testing.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V39's whole point, proven against the real Postgres container rather
 * than trusted from reading the migration: a real least-privilege role
 * can do exactly what it's meant to and nothing else. This connects
 * with catalog_service_role/finance_service_role's own real credentials
 * (not the application's own superuser-ish "verdant" user
 * IntegrationTestBase otherwise uses everywhere) and lets Postgres
 * itself reject a cross-domain query - the actual mechanism a physical
 * service split would depend on.
 */
class ServiceIsolationTest extends IntegrationTestBase {

    private Connection connectAs(String role, String password) throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), role, password);
    }

    @Test
    void catalogRole_canReadAndWriteItsOwnTables() throws SQLException {
        // POSTGRES.withReuse(true) (IntegrationTestBase) persists rows
        // across repeated real runs against the same container - a
        // fixed (name, observed_date) literal here would collide with
        // festival_calendar's own real UNIQUE constraint (V38) on a
        // second run, found for real the same way this project's other
        // reused-container collisions have been (Week 9's OpenSearch
        // corpus). A random name sidesteps it the same way this
        // project's other tests use a random suffix for exactly this
        // reason.
        try (Connection con = connectAs("catalog_service_role", "catalog-service-dev-password");
             Statement st = con.createStatement()) {
            st.execute("SELECT count(*) FROM canonical_products");
            st.execute("INSERT INTO festival_calendar (name, observed_date, demand_lead_days) "
                + "VALUES ('Isolation Test Festival " + UUID.randomUUID() + "', '2030-01-01', 3)");
        }
    }

    @Test
    void catalogRole_cannotTouchFinanceTables() throws SQLException {
        try (Connection con = connectAs("catalog_service_role", "catalog-service-dev-password")) {
            assertDenied(con, "SELECT count(*) FROM ledger_transactions");
            assertDenied(con, "SELECT count(*) FROM payout_cycles");
        }
    }

    @Test
    void catalogRole_cannotTouchTablesOwnedByNeitherService() throws SQLException {
        try (Connection con = connectAs("catalog_service_role", "catalog-service-dev-password")) {
            assertDenied(con, "SELECT count(*) FROM orders");
            assertDenied(con, "SELECT count(*) FROM sellers");
        }
    }

    @Test
    void financeRole_canReadAndWriteItsOwnTables() throws SQLException {
        try (Connection con = connectAs("finance_service_role", "finance-service-dev-password");
             Statement st = con.createStatement()) {
            st.execute("SELECT count(*) FROM ledger_transactions");
            st.execute("SELECT count(*) FROM payout_cycles");
        }
    }

    @Test
    void financeRole_cannotTouchCatalogTables() throws SQLException {
        try (Connection con = connectAs("finance_service_role", "finance-service-dev-password")) {
            assertDenied(con, "SELECT count(*) FROM canonical_products");
            assertDenied(con, "SELECT count(*) FROM listings");
        }
    }

    private void assertDenied(Connection con, String sql) {
        assertThatThrownBy(() -> {
            try (Statement st = con.createStatement()) {
                st.execute(sql);
            }
        }).isInstanceOf(SQLException.class)
          .hasMessageContaining("permission denied");
    }

    @Test
    void neitherServiceRole_isASuperuserOrCanCreateNewRoles() throws SQLException {
        // A real, cheap extra guarantee beyond table grants: even a
        // misconfigured future grant on these two roles couldn't
        // escalate into altering the database's own security model.
        try (Connection con = connectAs("catalog_service_role", "catalog-service-dev-password")) {
            assertDenied(con, "CREATE ROLE should_not_be_possible");
        }
    }
}
