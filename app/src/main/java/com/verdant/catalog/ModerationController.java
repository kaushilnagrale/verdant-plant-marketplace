package com.verdant.catalog;

import com.verdant.catalog.mapping.MappingService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/moderation/listings")
public class ModerationController {

    private final JdbcTemplate jdbc;
    private final MappingService mapping;

    public ModerationController(JdbcTemplate jdbc, MappingService mapping) {
        this.jdbc = jdbc;
        this.mapping = mapping;
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
    }

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
    }
}
