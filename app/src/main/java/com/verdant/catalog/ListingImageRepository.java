package com.verdant.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ListingImageRepository extends JpaRepository<ListingImage, UUID> {
    List<ListingImage> findByListingId(UUID listingId);
    int countByListingId(UUID listingId);
}
