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

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

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
    public Instant getCreatedAt() { return createdAt; }
}
