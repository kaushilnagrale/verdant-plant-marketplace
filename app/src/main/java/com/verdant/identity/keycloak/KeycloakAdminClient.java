package com.verdant.identity.keycloak;

/**
 * Only the network-calling part of talking to Keycloak's admin API goes
 * behind this interface - same real split as PaymentGateway/CourierGateway:
 * the real implementation (KeycloakAdminRestClient) makes genuine REST
 * calls; the test profile swaps in StubKeycloakAdminClient so buyer
 * registration can be exercised against a real Postgres (Testcontainers)
 * without also standing up a real Keycloak realm inside every test.
 */
public interface KeycloakAdminClient {

    /** The new user's real Keycloak subject id (the "sub" claim every future JWT of theirs will carry). */
    String createBuyer(String email, String name, String phone, String password);

    /** Best-effort compensation if the local buyers row can't be created after the identity provider already accepted the new user. */
    void deleteUser(String subjectId);
}
