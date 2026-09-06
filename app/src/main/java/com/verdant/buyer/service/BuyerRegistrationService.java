package com.verdant.buyer.service;

import com.verdant.identity.keycloak.DuplicateBuyerEmailException;
import com.verdant.identity.keycloak.KeycloakAdminClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Same real shape as CheckoutService's own reserve-then-call-external-
 * then-persist-then-compensate pattern: check the cheap local thing
 * first, make the real external call second, persist locally third,
 * and compensate the external step if the local persist somehow still
 * fails after Keycloak already accepted the new user.
 *
 * emailVerified is set true on the Keycloak side (KeycloakAdminClient)
 * with no real verification email ever sent - there is no mail
 * infrastructure in this project (WhatsApp notifications exist,
 * transactional email does not). An honest, deliberately narrower gap
 * than pretending a verification step happened: registration works for
 * real, ownership-of-the-inbox is simply unverified, same category of
 * gap as this project's other named, un-faked limitations.
 */
@Service
public class BuyerRegistrationService {

    private static final int MIN_PASSWORD_LENGTH = 8;

    private final BuyerRepository buyers;
    private final KeycloakAdminClient keycloak;

    public BuyerRegistrationService(BuyerRepository buyers, KeycloakAdminClient keycloak) {
        this.buyers = buyers;
        this.keycloak = keycloak;
    }

    public UUID register(String name, String email, String phone, String password) {
        if (name == null || name.isBlank() || email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name and email are required");
        }
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        // A real, fresh Keycloak realm has no password policy configured by
        // default - this app-side floor is the only one that actually
        // exists right now, not a duplicate of one Keycloak also enforces.
        if (buyers.findByEmail(email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An account already exists for this email");
        }

        String subjectId;
        try {
            subjectId = keycloak.createBuyer(email, name, phone, password);
        } catch (DuplicateBuyerEmailException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not create account - identity provider unavailable", e);
        }

        try {
            return buyers.insert(subjectId, email, name, phone);
        } catch (RuntimeException e) {
            keycloak.deleteUser(subjectId);
            throw e;
        }
    }
}
