package com.verdant.catalog;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "listing_images")
public class ListingImage {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "listing_id", nullable = false)
    private UUID listingId;

    @Column(name = "s3_key", nullable = false)
    private String s3Key;

    @Column(nullable = false)
    private int position;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    protected ListingImage() { }

    public ListingImage(UUID listingId, String s3Key, int position) {
        this.listingId = listingId;
        this.s3Key = s3Key;
        this.position = position;
    }

    public UUID getId() { return id; }
    public UUID getListingId() { return listingId; }
    public String getS3Key() { return s3Key; }
    public int getPosition() { return position; }
}
