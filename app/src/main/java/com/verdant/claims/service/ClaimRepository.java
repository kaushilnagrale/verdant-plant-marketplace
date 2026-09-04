package com.verdant.claims.service;

import com.verdant.claims.domain.Claim;
import com.verdant.claims.domain.ClaimStatus;
import com.verdant.claims.domain.ClaimType;
import com.verdant.claims.domain.Fault;
import com.verdant.claims.domain.Resolution;
import com.verdant.claims.service.FaultAttributor.Attribution;
import com.verdant.common.domain.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@Repository
public class ClaimRepository {

    private final JdbcTemplate jdbc;

    public ClaimRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insert(UUID orderItemId, UUID shipmentId, String buyerSubjectId, ClaimType type) {
        return jdbc.queryForObject("""
            INSERT INTO claims (order_item_id, shipment_id, buyer_subject_id, type, status)
            VALUES (?, ?, ?, ?, 'SUBMITTED') RETURNING id
            """, UUID.class, orderItemId, shipmentId, buyerSubjectId, type.name());
    }

    public void recordAttribution(UUID claimId, Attribution attribution) {
        ClaimStatus status = attribution.needsHumanReview() ? ClaimStatus.UNDER_REVIEW : ClaimStatus.APPROVED;
        jdbc.update("""
            UPDATE claims SET status = ?, fault = ?, fault_confidence = ?, fault_signals = ?::jsonb WHERE id = ?
            """, status.name(), attribution.fault().name(), BigDecimal.valueOf(attribution.confidence()),
            attribution.signalsJson(), claimId);
    }

    public void insertPhoto(UUID claimId, String s3Key) {
        jdbc.update("INSERT INTO claim_photos (claim_id, s3_key) VALUES (?, ?)", claimId, s3Key);
    }

    public Claim findById(UUID claimId) {
        return jdbc.queryForObject("""
            SELECT id, order_item_id, shipment_id, buyer_subject_id, type, status, fault, fault_confidence,
                   resolution, resolution_amount, ledger_txn_id, submitted_at, resolved_at, resolved_by
              FROM claims WHERE id = ?
            """, ROW_MAPPER, claimId);
    }

    /** Guarded by the caller's own idempotency check (claim.status == RESOLVED) - this just persists the decision once made. */
    public void recordResolution(UUID claimId, Resolution resolution, Money amount, UUID ledgerTxnId, String actorId) {
        jdbc.update("""
            UPDATE claims SET status = 'RESOLVED', resolution = ?, resolution_amount = ?,
                               ledger_txn_id = ?, resolved_at = now(), resolved_by = ?
             WHERE id = ?
            """, resolution.name(), amount.amount(), ledgerTxnId, actorId, claimId);
    }

    private static final RowMapper<Claim> ROW_MAPPER = ClaimRepository::mapRow;

    private static Claim mapRow(ResultSet rs, int rowNum) throws SQLException {
        String faultRaw = rs.getString("fault");
        String resolutionRaw = rs.getString("resolution");
        BigDecimal resolutionAmount = rs.getBigDecimal("resolution_amount");
        Timestamp resolvedAt = rs.getTimestamp("resolved_at");
        BigDecimal faultConfidence = rs.getBigDecimal("fault_confidence");

        return new Claim(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("order_item_id"),
            (UUID) rs.getObject("shipment_id"),
            rs.getString("buyer_subject_id"),
            ClaimType.valueOf(rs.getString("type")),
            ClaimStatus.valueOf(rs.getString("status")),
            faultRaw == null ? null : Fault.valueOf(faultRaw),
            faultConfidence,
            resolutionRaw == null ? null : Resolution.valueOf(resolutionRaw),
            resolutionAmount == null ? null : Money.of(resolutionAmount),
            (UUID) rs.getObject("ledger_txn_id"),
            rs.getTimestamp("submitted_at").toInstant(),
            resolvedAt == null ? null : resolvedAt.toInstant(),
            rs.getString("resolved_by"));
    }
}
