package com.verdant.search.query;

import com.verdant.search.projection.ListingDocument;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.aggregations.Aggregate;
import org.opensearch.client.opensearch._types.aggregations.AggregationRange;
import org.opensearch.client.opensearch._types.aggregations.StringTermsBucket;
import org.opensearch.client.opensearch._types.aggregations.RangeBucket;
import org.opensearch.client.opensearch._types.query_dsl.FieldValueFactorModifier;
import org.opensearch.client.opensearch._types.query_dsl.FunctionScore;
import org.opensearch.client.opensearch._types.query_dsl.FunctionScoreMode;
import org.opensearch.client.opensearch._types.query_dsl.FunctionBoostMode;
import org.opensearch.client.opensearch.core.search.Hit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Why price is not the primary signal: a cheaper plant six days away
 * arrives dead. A slightly pricier plant two days away arrives alive. So
 * ranking blends signals in different units, weighted with transit time
 * dominant, exactly as allocation (Week 6) does.
 *
 * "Serviceable origin zones" is real, per-product logic, not a static
 * list: max_transit_days varies by canonical product (already in the
 * index), and transit_days between two zones is real data
 * (transit_estimates, Week 6) - a fern with a 2-day limit and a palm with
 * a 6-day limit are serviceable from genuinely different sets of origin
 * zones for the same destination. Built here as one bool clause per
 * origin zone (term on origin_zone AND range on max_transit_days), not a
 * single flat allow-list, so a document is only matched from a zone that
 * can actually deliver it alive.
 */
@Service
public class ListingSearchService {

    private final OpenSearchClient opensearch;
    private final JdbcTemplate jdbc;

    public ListingSearchService(OpenSearchClient opensearch, JdbcTemplate jdbc) {
        this.opensearch = opensearch;
        this.jdbc = jdbc;
    }

    public SearchResults search(String text, String destinationPincode) throws IOException {
        String destZone = zoneFor(destinationPincode);
        Map<String, Integer> transitDaysByOriginZone = transitDaysTo(destZone);

        var response = opensearch.search(s -> s
            .index("listings")
            .size(50)
            // Facets deliberately run as real aggregations over every
            // matching document, not just this page's 50 hits - a buyer
            // filtering by "pet safe" needs to see how many of the ~400
            // matches are pet safe, not how many of the 50 shown happen
            // to be.
            .aggregations("light_requirement", a -> a.terms(t -> t.field("light_requirement")))
            .aggregations("size_tier", a -> a.terms(t -> t.field("size_tier")))
            .aggregations("pet_safe_true", a -> a.filter(f -> f.term(t -> t.field("pet_safe").value(FieldValue.of(true)))))
            .aggregations("pet_safe_false", a -> a.filter(f -> f.term(t -> t.field("pet_safe").value(FieldValue.of(false)))))
            .aggregations("price_range", a -> a.range(r -> r.field("price_amount").ranges(
                AggregationRange.of(rr -> rr.key("under_300").to("300")),
                AggregationRange.of(rr -> rr.key("300_to_600").from("300").to("600")),
                AggregationRange.of(rr -> rr.key("600_to_1200").from("600").to("1200")),
                AggregationRange.of(rr -> rr.key("over_1200").from("1200")))))
            // Delivery days isn't a field OpenSearch has - it's
            // (origin_zone, this request's own destination zone) looked up
            // in real transit_estimates (Postgres), so it can't be
            // aggregated natively. This bucket-by-origin-zone aggregation
            // is the real ingredient the facet is built from afterwards -
            // see buildDeliveryDaysFacet below.
            .aggregations("origin_zone", a -> a.terms(t -> t.field("origin_zone")))
            .query(q -> q.functionScore(fs -> fs
                .query(inner -> inner.bool(b -> {
                    b.must(m -> m.multiMatch(mm -> mm
                        .query(text)
                        .fields("display_name^3", "common_names^2", "botanical_name^2", "description")
                        .fuzziness("AUTO")));
                    b.filter(f -> f.term(t -> t.field("active").value(FieldValue.of(true))));
                    // Reachable from SOME zone at all, and (per zone)
                    // fast enough for THIS document's own max_transit_days -
                    // a should-clause per origin zone, at least one must match.
                    b.filter(f -> f.bool(inner2 -> {
                        transitDaysByOriginZone.forEach((zone, days) ->
                            inner2.should(sh -> sh.bool(zoneClause -> zoneClause
                                .must(zm -> zm.term(t -> t.field("origin_zone").value(FieldValue.of(zone))))
                                .must(zm -> zm.range(r -> r.field("max_transit_days").gte(org.opensearch.client.json.JsonData.of(days)))))));
                        return inner2.minimumShouldMatch("1");
                    }));
                    return b;
                }))
                .functions(List.of(
                    // Transit time: dominant signal - the destination's own zone, weighted heaviest.
                    FunctionScore.of(fn -> fn
                        .filter(f -> f.term(t -> t.field("origin_zone").value(FieldValue.of(destZone))))
                        .weight(3.0)),
                    // Seller quality.
                    FunctionScore.of(fn -> fn
                        .fieldValueFactor(fv -> fv.field("seller_quality_score").factor(1.5).missing(0.5))),
                    // In stock ranks higher, but out of stock stays discoverable (never filtered out).
                    FunctionScore.of(fn -> fn
                        .filter(f -> f.term(t -> t.field("in_stock").value(FieldValue.of(true))))
                        .weight(2.0)),
                    // Mild price preference - reciprocal, so a lower price scores (slightly) higher.
                    FunctionScore.of(fn -> fn
                        .fieldValueFactor(fv -> fv.field("price_amount").factor(0.1)
                            .modifier(FieldValueFactorModifier.Reciprocal).missing(1.0)))
                ))
                .scoreMode(FunctionScoreMode.Sum)
                .boostMode(FunctionBoostMode.Multiply)
            )),
            ListingDocument.class);

        List<SearchHit> hits = response.hits().hits().stream()
            .map(this::toHit)
            .toList();
        FacetCounts facets = buildFacets(response.aggregations(), transitDaysByOriginZone);
        return new SearchResults(hits, facets);
    }

    private FacetCounts buildFacets(Map<String, Aggregate> aggs, Map<String, Integer> transitDaysByOriginZone) {
        Map<String, Long> petSafe = new LinkedHashMap<>();
        petSafe.put("true", aggs.get("pet_safe_true").filter().docCount());
        petSafe.put("false", aggs.get("pet_safe_false").filter().docCount());

        return new FacetCounts(
            stringTermsToMap(aggs.get("light_requirement")),
            stringTermsToMap(aggs.get("size_tier")),
            petSafe,
            rangeToMap(aggs.get("price_range")),
            buildDeliveryDaysFacet(stringTermsToMap(aggs.get("origin_zone")), transitDaysByOriginZone));
    }

    private Map<String, Long> stringTermsToMap(Aggregate agg) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (StringTermsBucket bucket : agg.sterms().buckets().array()) {
            result.put(bucket.key(), bucket.docCount());
        }
        return result;
    }

    private Map<String, Long> rangeToMap(Aggregate agg) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (RangeBucket bucket : agg.range().buckets().array()) {
            result.put(bucket.key(), bucket.docCount());
        }
        return result;
    }

    /**
     * Real delivery time is a function of (origin_zone, destination_zone)
     * alone (transit_estimates) - max_transit_days is a per-PRODUCT
     * survival limit already spent filtering unserviceable origins out
     * upstream, not the delivery time itself. So every origin_zone bucket
     * that survived the filter maps to exactly one real day count for
     * THIS destination, and zones that happen to share a day count
     * (e.g. two zones both 3 days out) collapse into one facet bucket,
     * exactly as a buyer thinks about it ("arrives in 3 days"), not as
     * OpenSearch's own zone buckets happen to be shaped.
     */
    private Map<String, Long> buildDeliveryDaysFacet(Map<String, Long> originZoneCounts,
                                                       Map<String, Integer> transitDaysByOriginZone) {
        Map<Integer, Long> countsByDays = new TreeMap<>();
        originZoneCounts.forEach((zone, count) -> {
            Integer days = transitDaysByOriginZone.get(zone);
            if (days != null) {
                countsByDays.merge(days, count, Long::sum);
            }
        });
        Map<String, Long> result = new LinkedHashMap<>();
        countsByDays.forEach((days, count) -> result.put(days + (days == 1 ? " day" : " days"), count));
        return result;
    }

    private SearchHit toHit(Hit<ListingDocument> hit) {
        ListingDocument doc = hit.source();
        float score = hit.score() == null ? 0f : hit.score().floatValue();
        return new SearchHit(doc.listingId(), doc.sellerId(), doc.displayName(), doc.priceAmount(), doc.inStock(), doc.originZone(), score);
    }

    private String zoneFor(String pincode) {
        return jdbc.queryForObject("SELECT zone FROM pincode_zones WHERE pincode = ?", String.class, pincode);
    }

    /** Every origin zone that can reach destZone at all, and how many days it takes - compared per-document against that document's own max_transit_days. */
    private Map<String, Integer> transitDaysTo(String destZone) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT origin_zone, transit_days FROM transit_estimates WHERE destination_zone = ?", destZone);
        return rows.stream().collect(java.util.stream.Collectors.toMap(
            r -> (String) r.get("origin_zone"),
            r -> (Integer) r.get("transit_days")));
    }
}
