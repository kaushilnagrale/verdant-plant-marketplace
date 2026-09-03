package com.verdant.common.testing;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Runs after EVERY test, not just allocation tests. canonical_products is
 * shared platform-wide with the Week 3 catalog-mapping pipeline -
 * MappingAccuracyTest measures precision over every row in it. Any test
 * using AllocationFixtures.seedCanonicalProduct() leaves a real row behind
 * unless something finds and removes it; the display-name prefix is how
 * this finds exactly (and only) those rows, regardless of which test class
 * created them. Registered once here rather than as an @AfterEach in every
 * one of the 6+ test files that need it.
 *
 * Deliberately does NOT delete the listings themselves - real checkout
 * tests (Week 7) create real order_items/reservations/allocations rows
 * against these listings with no ON DELETE CASCADE (confirmed empirically:
 * an earlier version of this extension that tried to delete listings threw
 * a real DataIntegrityViolationException on every single test, not
 * assumed), and there's no need to chase that whole chain - the listing
 * row itself is harmless to MappingAccuracyTest, which only ever queries
 * canonical_products directly. Nulling the FK link is enough to let the
 * canonical_products row go.
 */
public class AllocationFixtureCleanupExtension implements AfterEachCallback {

    @Override
    public void afterEach(ExtensionContext context) {
        var ctx = SpringExtension.getApplicationContext(context);
        JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);

        String prefix = AllocationFixtures.DISPLAY_NAME_PREFIX + "%";
        jdbc.update("""
            UPDATE listings SET canonical_product_id = NULL
             WHERE canonical_product_id IN (SELECT id FROM canonical_products WHERE display_name LIKE ?)
            """, prefix);
        jdbc.update("DELETE FROM canonical_products WHERE display_name LIKE ?", prefix);
    }
}
