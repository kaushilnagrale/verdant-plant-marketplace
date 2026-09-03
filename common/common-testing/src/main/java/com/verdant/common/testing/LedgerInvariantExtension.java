package com.verdant.common.testing;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs after EVERY test in the suite, not just ledger tests. Each individual
 * ledger transaction is already forced to balance by the database's
 * deferred constraint trigger (V14) - this checks something different: that
 * the sum across EVERY entry ever written, platform-wide, is still exactly
 * zero. If any code path anywhere ever manages to write an entry outside
 * the normal LedgerService.post() flow, this is what catches it, and it
 * points straight at whichever test just ran.
 */
public class LedgerInvariantExtension implements AfterEachCallback {

    @Override
    public void afterEach(ExtensionContext context) {
        var ctx = SpringExtension.getApplicationContext(context);
        JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);

        BigDecimal total = jdbc.queryForObject(
            "SELECT COALESCE(SUM(amount), 0) FROM ledger_entries", BigDecimal.class);

        assertThat(total)
            .withFailMessage("""
                LEDGER DOES NOT BALANCE GLOBALLY: %s
                Some code path in this test created or destroyed money.
                """, total)
            .isEqualByComparingTo(BigDecimal.ZERO);
    }
}
