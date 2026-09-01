package com.verdant.catalog;

import com.verdant.common.testing.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class ListingIsolationTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;

    @Test
    void listingImages_areIsolatedThroughParentListing() {
        SellerListing a = sellerWithListingAndImage("A");
        SellerListing b = sellerWithListingAndImage("B");

        jdbc.execute("SET ROLE verdant_app");
        jdbc.execute("SET app.current_tenant = '" + a.sellerId() + "'");

        Integer visibleCount = jdbc.queryForObject("SELECT count(*) FROM listing_images", Integer.class);
        UUID visibleListingId = jdbc.queryForObject("SELECT listing_id FROM listing_images", UUID.class);

        jdbc.execute("RESET ROLE");

        assertThat(visibleCount).isEqualTo(1);
        assertThat(visibleListingId).isEqualTo(a.listingId());
    }

    @Test
    void inactiveListing_isNotReturnedInPublicQuery() { }

    private SellerListing sellerWithListingAndImage(String label) {
        UUID sellerId = jdbc.queryForObject(
            "INSERT INTO sellers (business_name, status) VALUES (?, 'APPROVED') RETURNING id",
            UUID.class, "Nursery " + label);
        UUID listingId = jdbc.queryForObject(
            "INSERT INTO listings (seller_id, raw_title, price_amount) VALUES (?, ?, 100.00) RETURNING id",
            UUID.class, sellerId, "Plant " + label);
        jdbc.update("INSERT INTO listing_images (listing_id, s3_key, position) VALUES (?, ?, 0)",
            listingId, "listings/" + listingId + "/thumb.jpg");
        return new SellerListing(sellerId, listingId);
    }

    private record SellerListing(UUID sellerId, UUID listingId) { }
}
