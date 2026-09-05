package com.verdant.catalog.mapping;

import com.verdant.catalog.mapping.MappingResult.Status;
import com.verdant.common.outbox.OutboxWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Component
public class ListingMappingWorker {

    private final MappingService mapping;
    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;

    public ListingMappingWorker(MappingService mapping, JdbcTemplate jdbc, OutboxWriter outbox) {
        this.mapping = mapping;
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    // @Transactional added for the same reason as ListingController/
    // InventoryService (Week 9): OutboxWriter.write() is MANDATORY
    // propagation, and this method's own writes were plain,
    // independently-committing JdbcTemplate calls with no enclosing
    // transaction before now. Safe to put directly on an @Async method -
    // @Async itself only works through Spring's proxy in the first place,
    // so there is no self-invocation path here to worry about.
    @Async
    @Transactional
    public void mapListing(UUID listingId, String rawTitle, String description) {
        MappingResult result = mapping.map(rawTitle, description);

        String status = switch (result.status()) {
            case AUTO_MAPPED -> "AUTO_MAPPED";
            case PENDING_REVIEW -> "PENDING_REVIEW";
            case UNMAPPED -> "UNMAPPED";
        };

        jdbc.update("""
            UPDATE listings
               SET canonical_product_id = ?, mapping_confidence = ?, mapping_status = ?
             WHERE id = ?
            """, result.canonicalProductId(), (float) result.confidence(), status, listingId);

        if (result.status() != Status.UNMAPPED) {
            UUID topCandidate = result.status() == Status.AUTO_MAPPED
                ? result.canonicalProductId()
                : result.candidates().get(0).canonicalProductId();
            jdbc.update("""
                INSERT INTO mapping_decisions (listing_id, canonical_product_id, confidence, decided_by)
                VALUES (?, ?, ?, 'AUTO')
                """, listingId, topCandidate, (float) result.confidence());
        }

        outbox.write("LISTING", listingId, "LISTING_MAPPED", Map.of("listingId", listingId.toString()));
    }
}
