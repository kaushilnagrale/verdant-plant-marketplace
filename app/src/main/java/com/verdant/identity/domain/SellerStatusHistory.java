package com.verdant.identity.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "seller_status_history")
public class SellerStatusHistory {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "seller_id", nullable = false)
    private UUID sellerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    private SellerStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false)
    private SellerStatus toStatus;

    @Column(name = "actor_id", nullable = false)
    private String actorId;

    private String reason;

    @Column(insertable = false, updatable = false)
    private Instant at;

    protected SellerStatusHistory() { }

    public SellerStatusHistory(UUID sellerId, SellerStatus fromStatus, SellerStatus toStatus, String actorId, String reason) {
        this.sellerId = sellerId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actorId = actorId;
        this.reason = reason;
    }

    public UUID getSellerId() { return sellerId; }
    public SellerStatus getFromStatus() { return fromStatus; }
    public SellerStatus getToStatus() { return toStatus; }
}
