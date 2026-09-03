package com.verdant.common.flags;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Maps feature_flags 1:1 - a small, platform-wide (not tenant-scoped) table, no RLS. */
@Entity
@Table(name = "feature_flags")
public class FeatureFlagEntity {

    @Id
    private String name;

    private boolean enabled;
    private boolean shadowMode;

    protected FeatureFlagEntity() { }

    public String getName() { return name; }
    public boolean isEnabled() { return enabled; }
    public boolean isShadowMode() { return shadowMode; }
}
