package com.verdant;

import com.verdant.common.testing.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class TenantIsolationTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;

    @Test
    void rlsPolicy_blocksCrossSellerRead_evenViaDirectSql() {
        UUID a = insertSeller("A");
        UUID b = insertSeller("B");
        insertUser(a, "sub-a-" + a);
        insertUser(b, "sub-b-" + b);

        jdbc.execute("SET ROLE verdant_app");
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
