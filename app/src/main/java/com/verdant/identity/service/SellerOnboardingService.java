package com.verdant.identity.service;

import com.verdant.identity.domain.*;
import com.verdant.identity.repository.SellerRepository;
import com.verdant.identity.repository.SellerStatusHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class SellerOnboardingService {

    private final SellerRepository sellers;
    private final SellerStatusHistoryRepository history;

    public SellerOnboardingService(SellerRepository sellers, SellerStatusHistoryRepository history) {
        this.sellers = sellers;
        this.history = history;
    }

    @Transactional
    public Seller createDraft(String businessName) {
        return sellers.save(new Seller(businessName));
    }

    @Transactional
    public Seller transition(UUID sellerId, SellerStatus next, String actorId, String reason) {
        Seller seller = sellers.findById(sellerId).orElseThrow(SellerNotFoundException::new);
        SellerStatus from = seller.getStatus();

        seller.transitionTo(next);                       // throws if illegal
        history.record(sellerId, from, next, actorId, reason);

        return seller;
    }

    @Transactional(readOnly = true)
    public SellerCapabilities capabilities(UUID sellerId) {
        return sellers.findById(sellerId).orElseThrow(SellerNotFoundException::new).capabilities();
    }
}
