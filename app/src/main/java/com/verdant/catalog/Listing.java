package com.verdant.catalog;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "listings")
public class Listing {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "seller_id", nullable = false)
    private UUID sellerId;

    @Column(name = "raw_title", nullable = false)
    private String rawTitle;

    @Column(name = "price_amount", nullable = false)
    private BigDecimal priceAmount;

    @Column(name = "handling_time_hours", nullable = false)
    private Integer handlingTimeHours = 24;

    // Real columns since V5 (Week 3) that this entity never mapped until
    // now - nothing needed to read or write them before ListingController
    // gained real update/deactivate endpoints (Week 9), so they simply
    // sat unused on every Listing row this whole project.
    @Column(name = "description")
    private String description;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    // Written only by ListingMappingWorker/ModerationController via raw JDBC,
    // never by JPA - insertable/updatable false so a stray entity save()
    // elsewhere can never blast a stale value back over an async update.
    @Column(name = "canonical_product_id", insertable = false, updatable = false)
    private UUID canonicalProductId;

    @Column(name = "mapping_confidence", insertable = false, updatable = false)
    private Float mappingConfidence;

    @Column(name = "mapping_status", insertable = false, updatable = false)
    private String mappingStatus;

    protected Listing() { } // required by JPA

    public Listing(UUID sellerId, String rawTitle, BigDecimal priceAmount) {
        this.sellerId = sellerId;
        this.rawTitle = rawTitle;
        this.priceAmount = priceAmount;
    }

    public UUID getId() { return id; }
    public UUID getSellerId() { return sellerId; }
    public String getRawTitle() { return rawTitle; }
    public BigDecimal getPriceAmount() { return priceAmount; }
    public Integer getHandlingTimeHours() { return handlingTimeHours; }
    public String getDescription() { return description; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public UUID getCanonicalProductId() { return canonicalProductId; }
    public Float getMappingConfidence() { return mappingConfidence; }
    public String getMappingStatus() { return mappingStatus; }

    public void updatePrice(BigDecimal newPrice) {
        this.priceAmount = newPrice;
    }

    /** One-way in practice - nothing in this project ever reactivates a listing, matching how a seller would actually use this (pull a listing down for good, not toggle it). */
    public void deactivate() {
        this.active = false;
    }
}
