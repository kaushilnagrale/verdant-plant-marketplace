package com.verdant.buyer;

import com.verdant.buyer.service.BuyerRegistrationService;
import com.verdant.buyer.service.BuyerRepository;
import com.verdant.common.testing.IntegrationTestBase;
import com.verdant.identity.keycloak.StubKeycloakAdminClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BuyerRegistrationServiceTest extends IntegrationTestBase {

    @Autowired private BuyerRegistrationService registration;
    @Autowired private BuyerRepository buyers;
    @Autowired private StubKeycloakAdminClient keycloakStub;

    @BeforeEach
    void reset() {
        keycloakStub.reset();
    }

    @Test
    void register_createsARealLocalRow_keyedByTheIdentityProvidersRealSubjectId() {
        String email = "svc-buyer-" + UUID.randomUUID() + "@example.com";

        UUID buyerId = registration.register("Service Test Buyer", email, "9000000000", "a-real-password");

        var buyer = buyers.findBySubjectId(buyers.findByEmail(email).orElseThrow().subjectId()).orElseThrow();
        assertThat(buyer.id()).isEqualTo(buyerId);
        assertThat(buyer.email()).isEqualTo(email);
    }

    /** The whole point of registering the identity-provider call behind an interface (KeycloakAdminClient) - a real failure there must never leave a half-created account. */
    @Test
    void register_whenIdentityProviderFails_leavesNoLocalRowBehind() {
        keycloakStub.failNextCall();
        String email = "failed-buyer-" + UUID.randomUUID() + "@example.com";

        assertThatThrownBy(() -> registration.register("Doomed Buyer", email, null, "a-real-password"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("502");

        assertThat(buyers.findByEmail(email)).isEmpty();
    }
}
