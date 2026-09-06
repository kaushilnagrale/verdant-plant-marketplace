package com.verdant.identity.keycloak;

import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Real Keycloak admin REST calls (plain RestClient, same pattern as
 * ShiprocketGateway/OpenWeatherClient - no admin SDK dependency) so
 * buyer-web can offer a real, custom-styled registration form instead
 * of redirecting to Keycloak's own hosted pages. Authenticates as its
 * own service-account client (KeycloakAdminProperties), never the
 * platform's bootstrap master-realm admin.
 */
@Component
@Profile("!test")
public class KeycloakAdminRestClient implements KeycloakAdminClient {

    private final KeycloakAdminProperties props;
    private final RestClient http;

    public KeycloakAdminRestClient(KeycloakAdminProperties props) {
        this.props = props;
        this.http = RestClient.create();
    }

    @Override
    public String createBuyer(String email, String name, String phone, String password) {
        String token = serviceAccountToken();

        JSONObject credential = new JSONObject()
            .put("type", "password")
            .put("value", password)
            .put("temporary", false);
        JSONObject body = new JSONObject()
            .put("username", email)
            .put("email", email)
            .put("enabled", true)
            .put("emailVerified", true) // no real mailbox-verification flow exists (or a mail server) - see BuyerRegistrationService's own doc comment
            .put("firstName", name)
            .put("credentials", new JSONArray().put(credential));
        if (phone != null && !phone.isBlank()) {
            body.put("attributes", new JSONObject().put("phone", new JSONArray().put(phone)));
        }

        ResponseEntity<Void> response;
        try {
            response = http.post()
                .uri(props.baseUrl() + "/admin/realms/" + props.realm() + "/users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.toString())
                .retrieve()
                .toBodilessEntity();
        } catch (HttpClientErrorException.Conflict e) {
            throw new DuplicateBuyerEmailException(email);
        }

        String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
        if (location == null) {
            throw new IllegalStateException("Keycloak did not return a Location header for the new user");
        }
        String subjectId = location.substring(location.lastIndexOf('/') + 1);

        assignBuyerRole(token, subjectId);
        return subjectId;
    }

    @Override
    public void deleteUser(String subjectId) {
        try {
            String token = serviceAccountToken();
            http.delete()
                .uri(props.baseUrl() + "/admin/realms/" + props.realm() + "/users/" + subjectId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .toBodilessEntity();
        } catch (RuntimeException ignored) {
            // Best-effort only - an orphaned Keycloak user with no local
            // buyers row is a real, bounded cleanup task, not a data
            // integrity problem (they simply can never resolve to a
            // buyer via BuyerRepository, the same "no matching row" shape
            // as any other failed lookup).
        }
    }

    /** Role-mapping assignment needs the role's own full representation, not just its name - one extra real round trip. */
    private void assignBuyerRole(String token, String subjectId) {
        String role = http.get()
            .uri(props.baseUrl() + "/admin/realms/" + props.realm() + "/roles/BUYER")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .retrieve()
            .body(String.class);

        http.post()
            .uri(props.baseUrl() + "/admin/realms/" + props.realm() + "/users/" + subjectId + "/role-mappings/realm")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .body("[" + role + "]")
            .retrieve()
            .toBodilessEntity();
    }

    private String serviceAccountToken() {
        String response = http.post()
            .uri(props.baseUrl() + "/realms/" + props.realm() + "/protocol/openid-connect/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body("grant_type=client_credentials&client_id=" + props.clientId() + "&client_secret=" + props.clientSecret())
            .retrieve()
            .body(String.class);
        return new JSONObject(response).getString("access_token");
    }
}
