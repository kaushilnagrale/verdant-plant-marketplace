package com.verdant.identity.service;

import com.verdant.common.outbox.OutboxWriter;
import com.verdant.identity.domain.*;
import com.verdant.identity.repository.SellerRepository;
import com.verdant.identity.repository.SellerStatusHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class SellerOnboardingService {

    private final SellerRepository sellers;
    private final SellerStatusHistoryRepository history;
    private final OutboxWriter outbox;

    public SellerOnboardingService(SellerRepository sellers, SellerStatusHistoryRepository history, OutboxWriter outbox) {
        this.sellers = sellers;
        this.history = history;
        this.outbox = outbox;
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

        // Only the two transitions the search projector's fan-out
        // actually handles (Week 9) - a suspended seller's listings must
        // stop appearing in results, an approved seller's start being
        // eligible. Every other transition (DRAFT, PENDING_REVIEW,
        // OFFBOARDED) has no listings to reindex yet or - for OFFBOARDED -
        // is a genuinely separate gap, not addressed by this event set.
        if (next == SellerStatus.SUSPENDED) {
            outbox.write("SELLER", sellerId, "SELLER_SUSPENDED", Map.of("sellerId", sellerId.toString()));
        } else if (next == SellerStatus.APPROVED) {
            outbox.write("SELLER", sellerId, "SELLER_APPROVED", Map.of("sellerId", sellerId.toString()));
        }

        return seller;
    }

    @Transactional(readOnly = true)
    public SellerCapabilities capabilities(UUID sellerId) {
        return sellers.findById(sellerId).orElseThrow(SellerNotFoundException::new).capabilities();
    }
}
