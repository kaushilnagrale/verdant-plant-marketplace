package com.verdant.search.query;

import java.util.List;

public record SearchResults(List<SearchHit> hits, FacetCounts facets) {
}
