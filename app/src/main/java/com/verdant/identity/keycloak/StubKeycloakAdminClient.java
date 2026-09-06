package com.verdant.identity.keycloak;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Same real stub-gateway pattern as StubPaymentGateway/StubCourierGateway - a real Postgres (Testcontainers) is exercised for real; no real Keycloak realm needs to exist inside a test. */
@Component
@Profile("test")
public class StubKeycloakAdminClient implements KeycloakAdminClient {

    private final Set<String> emails = ConcurrentHashMap.newKeySet();
    private final Map<String, String> subjectIdByEmail = new ConcurrentHashMap<>();
    private boolean nextCallFails = false;

    @Override
    public String createBuyer(String email, String name, String phone, String password) {
        if (nextCallFails) {
            nextCallFails = false;
            throw new RuntimeException("Simulated identity provider failure");
        }
        if (!emails.add(email)) {
            throw new DuplicateBuyerEmailException(email);
        }
        String subjectId = UUID.randomUUID().toString();
        subjectIdByEmail.put(email, subjectId);
        return subjectId;
    }

    @Override
    public void deleteUser(String subjectId) {
        subjectIdByEmail.values().remove(subjectId);
        emails.removeIf(email -> subjectId.equals(subjectIdByEmail.get(email)));
    }

    /** Simulates Keycloak itself being unreachable - BuyerRegistrationServiceTest's own "identity provider unavailable" scenario. */
    public void failNextCall() {
        nextCallFails = true;
    }

    public void reset() {
        emails.clear();
        subjectIdByEmail.clear();
        nextCallFails = false;
    }
}
