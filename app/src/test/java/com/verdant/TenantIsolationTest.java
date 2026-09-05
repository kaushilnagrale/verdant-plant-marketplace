package com.verdant;

import com.verdant.common.testing.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class TenantIsolationTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;

    /**
     * @Transactional here is load-bearing, not incidental - outside a
     * transaction, plain JdbcTemplate calls each check out and release
     * their own connection independently. TenantAwareDataSource (Week 8)
     * re-applies its own default tenant context on every single
     * getConnection() call, so the SET ROLE / SET app.current_tenant
     * sequence below would get silently stomped between statements
     * without one connection held for the whole method - confirmed for
     * real (a genuine IncorrectResultSizeDataAccessException, not
     * assumed) before this annotation was added. Also conveniently
     * auto-rolls back the throwaway sellers/users this test inserts.
     */
    @Test
    @Transactional
    void rlsPolicy_blocksCrossSellerRead_evenViaDirectSql() {
        UUID a = insertSeller("A");
        UUID b = insertSeller("B");
        insertUser(a, "sub-a-" + a);
        insertUser(b, "sub-b-" + b);

        jdbc.execute("SET ROLE verdant_app");
        // TenantAwareDataSource (Week 8) now sets app.is_platform_admin =
        // 'true' by default on every connection this pool hands out (see
        // its own doc comment for why - background jobs need full access).
        // This test wants to isolate ONE narrow tenant specifically, so it
        // has to explicitly clear that default first, or the policy's own
        // "OR is_platform_admin" branch would satisfy every row regardless
        // of current_tenant below - confirmed for real, not assumed: this
        // test failed with count 37 (every seller_users row in the shared
        // database) before this line was added.
        jdbc.execute("RESET app.is_platform_admin");
        jdbc.execute("SET app.current_tenant = '" + a + "'");

        Integer visible = jdbc.queryForObject(
            "SELECT count(*) FROM seller_users", Integer.class);

        jdbc.execute("RESET ROLE");

        assertThat(visible).isEqualTo(1);   // only seller A's user
    }

    private UUID insertSeller(String name) {
        return jdbc.queryForObject(
            "INSERT INTO sellers (business_name, status) VALUES (?, 'APPROVED') RETURNING id",
            UUID.class, name);
    }

    private void insertUser(UUID sellerId, String subject) {
        jdbc.update("INSERT INTO seller_users (seller_id, subject_id, role) VALUES (?, ?, 'OWNER')",
            sellerId, subject);
    }
}
