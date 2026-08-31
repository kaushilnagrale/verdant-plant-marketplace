package com.verdant.identity;

import com.verdant.common.testing.IntegrationTestBase;
import com.verdant.identity.domain.*;
import com.verdant.identity.repository.SellerRepository;
import com.verdant.identity.repository.SellerStatusHistoryRepository;
import com.verdant.identity.service.SellerOnboardingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class SellerOnboardingTest extends IntegrationTestBase {

    @Autowired SellerOnboardingService service;
    @Autowired SellerRepository sellers;
    @Autowired SellerStatusHistoryRepository history;

    @Test
    void sellerWithUnverifiedBankAccount_cannotReceiveOrders() {
        UUID id = approvedSeller();
        SellerCapabilities caps = service.capabilities(id);

        assertThat(caps.canList()).isTrue();              // can build catalog
        assertThat(caps.canReceiveOrders()).isFalse();    // but not sell yet
        assertThat(caps.canReceivePayouts()).isFalse();
    }

    @Test
    void suspendedSeller_cannotReceiveNewOrders() {
        UUID id = fullyOnboardedSeller();
        assertThat(service.capabilities(id).canReceiveOrders()).isTrue();

        service.transition(id, SellerStatus.SUSPENDED, "ops-1", "quality");

        assertThat(service.capabilities(id).canReceiveOrders()).isFalse();
    }

    @Test
    void illegalTransition_isRejectedAndLeavesStatusUnchanged() {
        UUID id = service.createDraft("Nursery").getId();

        assertThatThrownBy(() -> service.transition(id, SellerStatus.APPROVED, "ops-1", null))
            .isInstanceOf(IllegalStateTransitionException.class);

        assertThat(service.capabilities(id).canList()).isFalse();
    }

    @Test
    void everyTransition_isRecordedInHistory() {
        UUID id = service.createDraft("Nursery").getId();
        service.transition(id, SellerStatus.PENDING_REVIEW, "seller", null);
        service.transition(id, SellerStatus.APPROVED, "ops-1", "docs ok");

        assertThat(historyRowsFor(id)).hasSize(2);
    }

    private UUID approvedSeller() {
        UUID id = service.createDraft("Nursery").getId();
        service.transition(id, SellerStatus.PENDING_REVIEW, "seller", null);
        service.transition(id, SellerStatus.APPROVED, "ops-1", "docs ok");
        return id;
    }

    private UUID fullyOnboardedSeller() {
        UUID id = approvedSeller();
        Seller seller = sellers.findById(id).orElseThrow();
        seller.markBankVerified();
        sellers.save(seller);
        return id;
    }

    private List<SellerStatusHistory> historyRowsFor(UUID id) {
        return history.findBySellerId(id);
    }
}
