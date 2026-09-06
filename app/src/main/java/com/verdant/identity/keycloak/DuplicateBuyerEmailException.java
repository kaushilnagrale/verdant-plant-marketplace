package com.verdant.identity.keycloak;

/** Keycloak itself already enforces username/email uniqueness - this is that real 409, not a locally-guessed check. */
public class DuplicateBuyerEmailException extends RuntimeException {
    public DuplicateBuyerEmailException(String email) {
        super("An account already exists for: " + email);
    }
}
