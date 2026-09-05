package com.verdant.search.projection;

import com.verdant.catalog.ListingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

/**
 * Consumes every event that can change a listing document. Idempotent by
 * construction: reprojection always reads current state fresh
 * (ProjectionQueryRepository), never replays the event's own payload, so
 * processing the same event twice - or out of order - produces the same
 * result either way.
 *
 * Real topic names, not the runbook's four grouped ones
 * ("listing-events" etc.): OutboxDrainer (Week 6) already derives one real
 * topic per event type ("verdant." + eventType.toLowerCase()), tested and
 * working since Day 31 - adapting to that established, already-produced
 * shape rather than inventing a grouped-topic scheme nothing in this
 * project's outbox actually publishes to. The event type is read back off
 * the topic name itself (@Header RECEIVED_TOPIC), not a payload field -
 * each topic already carries exactly one event type by construction, so
 * there's nothing to gain from also duplicating it inside the payload.
 *
 * autoStartup is bound to a property, defaulting false in
 * application-test.yml: this is the first real @KafkaListener in the
 * whole project, and without this, every one of the ~190 other tests in
 * this codebase - none of which have a real Kafka broker, only
 * IntegrationTestBase's Postgres+Redis - would have this listener
 * container attempt a real, doomed, noisy connection to a nonexistent
 * broker on every context boot. SearchIntegrationTestBase overrides it
 * back to true, since search tests do have a real Redpanda broker
 * (Testcontainers) to talk to.
 */
@Component
public class ListingProjector {

    private static final Logger log = LoggerFactory.getLogger(ListingProjector.class);
    private static final String TOPIC_PREFIX = "verdant.";

    private final SearchReindexQueueRepository reindexQueue;
    private final ListingRepository listings;
    private final ObjectMapper json;

    public ListingProjector(SearchReindexQueueRepository reindexQueue, ListingRepository listings, ObjectMapper json) {
        this.reindexQueue = reindexQueue;
        this.listings = listings;
        this.json = json;
    }

    @KafkaListener(
        topics = {
            "verdant.listing_created", "verdant.listing_updated", "verdant.listing_deactivated",
            "verdant.inventory_changed", "verdant.listing_mapped",
            "verdant.seller_suspended", "verdant.seller_approved", "verdant.seller_quality_changed"
        },
        groupId = "search-projector",
        autoStartup = "${verdant.search.projector.autostart:true}")
    public void onEvent(@Header(KafkaHeaders.RECEIVED_TOPIC) String topic, String payload) {
        String eventType = topic.substring(TOPIC_PREFIX.length()).toUpperCase();
        JsonNode node = json.readTree(payload);

        List<UUID> affected = switch (eventType) {
            case "LISTING_CREATED", "LISTING_UPDATED", "LISTING_DEACTIVATED", "INVENTORY_CHANGED", "LISTING_MAPPED"
                -> List.of(UUID.fromString(node.get("listingId").asString()));

            // One seller change can touch thousands of listings - fan out
            // here, not reindex inline, so this consumer stays fast and
            // never blocks the partition while a big seller's listings
            // queue up (the runbook's own point about SELLER_SUSPENDED).
            case "SELLER_SUSPENDED", "SELLER_APPROVED", "SELLER_QUALITY_CHANGED"
                -> listings.findIdsBySellerId(UUID.fromString(node.get("sellerId").asString()));

            default -> {
                log.warn("ListingProjector received an event type it doesn't handle: {}", eventType);
                yield List.of();
            }
        };

        affected.forEach(id -> reindexQueue.enqueue(id, eventType));
    }
}
