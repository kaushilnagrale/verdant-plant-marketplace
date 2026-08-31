package com.verdant.catalog;

import com.verdant.identity.SellerUserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/listings")
public class ListingController {

    private final ListingRepository listings;
    private final SellerUserRepository sellerUsers;

    public ListingController(ListingRepository listings, SellerUserRepository sellerUsers) {
        this.listings = listings;
        this.sellerUsers = sellerUsers;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Listing create(@AuthenticationPrincipal Jwt jwt, @RequestBody CreateListingRequest request) {
        UUID sellerId = currentSellerId(jwt);
        return listings.save(new Listing(sellerId, request.rawTitle(), request.priceAmount()));
    }

    @GetMapping
    public List<Listing> list(@AuthenticationPrincipal Jwt jwt) {
        return listings.findBySellerId(currentSellerId(jwt));
    }

    private UUID currentSellerId(Jwt jwt) {
        return sellerUsers.findBySubjectId(jwt.getSubject())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "No seller mapped to this account"))
            .getSellerId();
    }
}
