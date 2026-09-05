package com.verdant.catalog;

import com.verdant.identity.SellerUserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import com.verdant.catalog.mapping.ListingMappingWorker;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/listings")
public class ListingController {

    private final ListingRepository listings;
    private final SellerUserRepository sellerUsers;
    private final ListingImageService imageService;
    private final ListingMappingWorker mappingWorker;

    public ListingController(ListingRepository listings, SellerUserRepository sellerUsers,
                              ListingImageService imageService, ListingMappingWorker mappingWorker) {
        this.listings = listings;
        this.sellerUsers = sellerUsers;
        this.imageService = imageService;
        this.mappingWorker = mappingWorker;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Listing create(@AuthenticationPrincipal Jwt jwt, @RequestBody CreateListingRequest request) {
        UUID sellerId = currentSellerId(jwt);
        Listing saved = listings.save(new Listing(sellerId, request.rawTitle(), request.priceAmount()));
        mappingWorker.mapListing(saved.getId(), request.rawTitle(), null);
        return saved;
    }

    @GetMapping
    public List<Listing> list(@AuthenticationPrincipal Jwt jwt) {
        return listings.findBySellerId(currentSellerId(jwt));
    }

    // The ownership checks in get()/uploadImage() below look unreachable
    // for the cross-tenant case, and in practice they now are: with RLS
    // actually enforced (TenantAwareDataSource), a request scoped to the
    // caller's own tenant can no longer see another seller's row at all,
    // so findById() already returns empty and 404s before either check
    // runs. Left in place anyway as real defense in depth - the correct
    // behavior if RLS is ever misconfigured, disabled per-table, or this
    // code path is ever reached over a platform-admin (unscoped)
    // connection where every row IS visible.

    @GetMapping("/{id}")
    public Listing get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        UUID sellerId = currentSellerId(jwt);
        Listing listing = listings.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!listing.getSellerId().equals(sellerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your listing");
        }
        return listing;
    }

    @PostMapping("/{id}/images")
    @ResponseStatus(HttpStatus.CREATED)
    public void uploadImage(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                             @RequestParam("file") MultipartFile file) {
        UUID sellerId = currentSellerId(jwt);
        Listing listing = listings.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!listing.getSellerId().equals(sellerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your listing");
        }
        imageService.upload(id, file);
    }

    private UUID currentSellerId(Jwt jwt) {
        return sellerUsers.findBySubjectId(jwt.getSubject())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "No seller mapped to this account"))
            .getSellerId();
    }
}
