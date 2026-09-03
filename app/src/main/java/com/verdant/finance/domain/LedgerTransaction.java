package com.verdant.finance.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_transactions")
public class LedgerTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(nullable = false)
    private String kind;

    private String reference;

    @Column(name = "reverses_id")
    private UUID reversesId;

    @Column(name = "posted_at", insertable = false, updatable = false)
    private Instant postedAt;

    protected LedgerTransaction() { } // required by JPA

    public LedgerTransaction(String idempotencyKey, String kind, String reference) {
        this.idempotencyKey = idempotencyKey;
        this.kind = kind;
        this.reference = reference;
    }

    /** Links a reversal back to what it reverses - how you answer "why is this balance what it is." */
    public void markReverses(UUID originalId) {
        this.reversesId = originalId;
    }

    public UUID getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getKind() { return kind; }
    public String getReference() { return reference; }
    public UUID getReversesId() { return reversesId; }
    public Instant getPostedAt() { return postedAt; }
}
