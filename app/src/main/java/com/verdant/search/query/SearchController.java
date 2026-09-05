package com.verdant.search.query;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * Buyer-facing, unauthenticated, exactly like /v1/catalog (SecurityConfig)
 * - browsing plants for sale is not an account action, and requiring a
 * login to search would be a real, needless conversion-killer no
 * marketplace actually does.
 *
 * Not in the runbook's own Day 50 sketch at all - it goes straight from
 * ListingSearchService to a k6 script hitting "/v1/search?q=...&pincode=..."
 * at Day 52 with no controller ever shown in between. Built here, at the
 * point ranking itself is otherwise done, rather than left for Day 52 to
 * discover missing: a search feature genuinely isn't finished without a
 * real endpoint a buyer (or that load test) can call.
 *
 * A thrown IOException (a genuinely unreachable OpenSearch cluster) is
 * deliberately left to become a plain 500 rather than caught and turned
 * into an empty result set - an empty SearchResults reads to a buyer as
 * "nothing matches", which is a real, damaging false negative when the
 * true fact is "search is down right now". Same honesty-over-a-friendlier-
 * looking-response choice this project has made elsewhere.
 */
@RestController
@RequestMapping("/v1/search")
public class SearchController {

    private final ListingSearchService searchService;

    public SearchController(ListingSearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    public SearchResults search(@RequestParam("q") String query,
                                 @RequestParam("pincode") String destinationPincode) throws IOException {
        return searchService.search(query, destinationPincode);
    }
}
