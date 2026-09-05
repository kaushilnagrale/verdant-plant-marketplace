package com.verdant.assistant.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * One retrieved knowledge chunk with its real cosine-similarity score
 * against the query - the similarity floor (GiftConciergeService/
 * PlantDoctorService) and citation validation both key off id and
 * score. score is BigDecimal, not double - this project's own
 * ArchitectureRulesTest bans float/double fields anywhere under
 * "..domain.." (QualityScore/Tier hit the same rule, Week 10; Claim.faultConfidence
 * before that, Week 8) - the real pgvector query itself still computes
 * in double precision underneath, only the stored/passed-around field
 * needs to be exact.
 */
public record Scored(UUID id, String documentId, DocType docType, String heading, String text, BigDecimal score) {

    public static Scored of(UUID id, String documentId, DocType docType, String heading, String text, double score) {
        return new Scored(id, documentId, docType, heading, text, BigDecimal.valueOf(score).setScale(6, RoundingMode.HALF_UP));
    }
}
