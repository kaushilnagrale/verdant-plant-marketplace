import { apiClient } from "./client";
import type { SearchResults } from "../types";

// The real location-aware read path (ListingSearchService) - unlike
// /v1/catalog's plain "every active listing" list, this ranks by real
// transit time and drops listings whose product can't survive the trip
// to destinationPincode at all (see that class's own doc comment). Never
// wired into a UI screen until now, despite being built, tested, and
// load-tested since Week 9.
export async function search(query: string, destinationPincode: string): Promise<SearchResults> {
  const res = await apiClient.get<SearchResults>("/v1/search", { params: { q: query, pincode: destinationPincode } });
  return res.data;
}
