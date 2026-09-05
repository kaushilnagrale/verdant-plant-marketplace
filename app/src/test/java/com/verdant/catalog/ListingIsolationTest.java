package com.verdant.catalog;

import com.verdant.common.testing.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class ListingIsolationTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;

    /** @Transactional is load-bearing here - see TenantIsolationTest's own comment for why (one connection must be held for the whole SET ROLE / SET tenant / query sequence, or TenantAwareDataSource re-applies its default on every independent JdbcTemplate call). */
    @Test
    @Transactional
    void listingImages_areIsolatedThroughParentListing() {
        SellerListing a = sellerWithListingAndImage("A");
        SellerListing b = sellerWithListingAndImage("B");

        jdbc.execute("SET ROLE verdant_app");
        // See TenantIsolationTest's own comment - TenantAwareDataSource
        // (Week 8) defaults every connection to app.is_platform_admin =
        // 'true', which must be cleared before testing narrow tenant
        // scoping specifically, or the policy's admin-bypass branch
        // satisfies every row regardless of current_tenant below.
        jdbc.execute("RESET app.is_platform_admin");
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
