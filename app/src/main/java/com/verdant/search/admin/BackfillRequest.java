package com.verdant.search.admin;

/** The new index a full-catalog backfill should populate - e.g. "listings-v202609051200" (scripts/reindex-all.sh) - never the live "listings" alias itself, which is what blue-green reindexing exists to protect from a rebuild-in-place. */
public record BackfillRequest(String targetIndex) { }
