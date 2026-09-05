package com.verdant.catalog;

import com.verdant.catalog.mapping.MappingService;
import com.verdant.common.outbox.OutboxWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/moderation/listings")
public class ModerationController {

    private final JdbcTemplate jdbc;
    private final MappingService mapping;
    private final OutboxWriter outbox;

    public ModerationController(JdbcTemplate jdbc, MappingService mapping, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.mapping = mapping;
        this.outbox = outbox;
    }

    public record Suggestion(UUID canonicalProductId, double score) { }
    public record ModerationItem(UUID listingId, String rawTitle, List<Suggestion> suggestions) { }
    public record MapDecisionRequest(UUID canonicalProductId) { }

    @GetMapping
    public List<ModerationItem> pendingReview() {
        record Row(UUID id, String rawTitle) { }
        List<Row> rows = jdbc.query("""
            SELECT id, raw_title FROM listings
             WHERE mapping_status = 'PENDING_REVIEW'
             ORDER BY created_at
            """, (rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getString("raw_title")));

        return rows.stream().map(row -> {
            var result = mapping.map(row.rawTitle(), null);
            var suggestions = result.candidates().stream()
                .map(c -> new Suggestion(c.canonicalProductId(), c.score()))
                .toList();
            return new ModerationItem(row.id(), row.rawTitle(), suggestions);
        }).toList();
    }

    // @Transactional added on both (Week 9) - neither had any enclosing
    // transaction before, and OutboxWriter.write() is MANDATORY
    // propagation. A real, separate gap closed here beyond just adding
    // the event: map() is literally the only way a PENDING_REVIEW listing
    // ever becomes searchable at all - without something to trigger its
    // reindex afterward, a moderator approving a mapping would have had
    // no visible effect on search results, indefinitely.

    @Transactional
    @PostMapping("/{id}/map")
    public void map(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                     @RequestBody MapDecisionRequest request) {
        int updated = jdbc.update("""
            UPDATE listings SET canonical_product_id = ?, mapping_status = 'MANUAL', mapping_confidence = 1.0
             WHERE id = ?
            """, request.canonicalProductId(), id);
        if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);

        jdbc.update("""
            INSERT INTO mapping_decisions (listing_id, canonical_product_id, confidence, decided_by, actor_id)
            VALUES (?, ?, 1.0, 'HUMAN', ?)
            """, id, request.canonicalProductId(), jwt.getSubject());

        outbox.write("LISTING", id, "LISTING_MAPPED", Map.of("listingId", id.toString()));
    }

    @Transactional
    @PostMapping("/{id}/reject")
    public void reject(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        int updated = jdbc.update("""
            UPDATE listings SET mapping_status = 'UNMAPPED', canonical_product_id = NULL, mapping_confidence = 0
             WHERE id = ?
            """, id);
        if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);

        jdbc.update("""
            INSERT INTO mapping_decisions (listing_id, canonical_product_id, confidence, decided_by, actor_id)
            VALUES (?, NULL, 0.0, 'HUMAN', ?)
            """, id, jwt.getSubject());

        outbox.write("LISTING", id, "LISTING_MAPPED", Map.of("listingId", id.toString()));
    }
}
