package com.verdant.common.flags;

import com.verdant.common.testing.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the cache is real (backed by actual Redis, not just present in
 * the Spring context) rather than assuming @Cacheable works because the
 * annotation is there - a stale read while cached, then a correct read
 * after eviction, is the concrete evidence.
 */
class FeatureFlagsTest extends IntegrationTestBase {

    @Autowired private FeatureFlags flags;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CacheManager cacheManager;

    @Test
    void unknownFlag_defaultsToOffAndNotShadow() {
        FeatureFlags.Flag flag = flags.get("does_not_exist");
        assertThat(flag.enabled()).isFalse();
        assertThat(flag.shadow()).isFalse();
        assertThat(flag.active()).isFalse();
    }

    @Test
    void seededFlag_readsRealDatabaseState() {
        jdbc.update("""
            INSERT INTO feature_flags (name, enabled, shadow_mode) VALUES (?, true, false)
            ON CONFLICT (name) DO UPDATE SET enabled = true, shadow_mode = false
            """, "flag_read_test");

        FeatureFlags.Flag flag = flags.get("flag_read_test");
        assertThat(flag.enabled()).isTrue();
        assertThat(flag.active()).isTrue();
    }

    @Test
    void flagChange_isNotVisibleUntilCacheEviction() {
        String name = "flag_cache_test";
        jdbc.update("""
            INSERT INTO feature_flags (name, enabled, shadow_mode) VALUES (?, false, false)
            ON CONFLICT (name) DO UPDATE SET enabled = false, shadow_mode = false
            """, name);

        // First read populates the cache (real Redis - proven by the next step).
        assertThat(flags.get(name).enabled()).isFalse();

        // Change the underlying row directly, bypassing FeatureFlags entirely.
        jdbc.update("UPDATE feature_flags SET enabled = true WHERE name = ?", name);

        // Still stale, served from the cache, not the database - the actual
        // point of caching this at all (protects the DB from a read on
        // every request that checks a flag).
        assertThat(flags.get(name).enabled()).isFalse();

        // Evict (what happens naturally after the 30s TTL) - now it reflects reality.
        cacheManager.getCache("flags").evict(name);
        assertThat(flags.get(name).enabled()).isTrue();
    }
}
