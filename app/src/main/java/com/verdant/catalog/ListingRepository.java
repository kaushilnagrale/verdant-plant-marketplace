package com.verdant.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.UUID;

public interface ListingRepository extends JpaRepository<Listing, UUID> {
    List<Listing> findBySellerId(UUID sellerId);

    /** A lighter projection than findBySellerId() - ListingProjector's seller-suspension fan-out (Week 9) only ever needs the ids to enqueue, never the full entities. */
    @Query("SELECT l.id FROM Listing l WHERE l.sellerId = :sellerId")
    List<UUID> findIdsBySellerId(@Param("sellerId") UUID sellerId);
}
