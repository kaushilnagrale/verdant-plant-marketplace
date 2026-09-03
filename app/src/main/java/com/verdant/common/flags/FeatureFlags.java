package com.verdant.common.flags;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

/**
 * A flag flip in the database takes effect within the "flags" cache's TTL
 * (spring.cache.redis.time-to-live, application.yml) - short enough that
 * flipping a flag is fast without hammering the database on every request
 * that checks one. Backed by real Redis (not an in-process cache) so every
 * app instance sees the same flag state within the same window, not each
 * on its own clock - see CacheConfig for why that matters at more than one
 * instance.
 */
@Component
public class FeatureFlags {

    private final FeatureFlagRepository repo;

    public FeatureFlags(FeatureFlagRepository repo) { this.repo = repo; }

    @Cacheable(value = "flags", key = "#name")
    public Flag get(String name) {
        return repo.findById(name)
                   .map(f -> new Flag(f.isEnabled(), f.isShadowMode()))
                   .orElse(new Flag(false, false));
    }

    public record Flag(boolean enabled, boolean shadowMode) {
        public boolean active() { return enabled && !shadowMode; }
        public boolean shadow() { return shadowMode; }
    }
}
