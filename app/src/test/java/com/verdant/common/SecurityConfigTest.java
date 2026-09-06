package com.verdant.common;

import com.verdant.common.testing.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real, previously-invisible bug (found Week 7 by an actual curl round-trip
 * against a running app, not by any prior test): a thrown
 * ResponseStatusException on a permitAll endpoint got internally
 * redispatched by Spring MVC to /error, which re-enters the SAME security
 * filter chain - and /error wasn't permitted, so every 4xx/5xx from a
 * permitAll endpoint (cart, checkout, webhooks, catalog) was silently
 * masked as 401 Unauthorized instead of its real status.
 *
 * MUST use a real embedded server (webEnvironment = RANDOM_PORT +
 * TestRestTemplate), NOT MockMvc - verified directly (temporarily reverted
 * the fix and reran): the exact same assertion via @AutoConfigureMockMvc
 * passed regardless of whether the fix was present, because MockMvc does
 * not perform a real servlet-container error dispatch through the actual
 * filter chain the way a genuine HTTP round-trip does. Only a real socket
 * request reproduces this class of bug - the same reason the original
 * finding came from curl against a running app, not any automated test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class SecurityConfigTest extends IntegrationTestBase {

    @Autowired private TestRestTemplate rest;

    @Test
    void errorResponseFromAPermitAllEndpoint_keepsItsRealStatus_notMaskedAs401() {
        // /v1/checkout itself is no longer permitAll (buyer accounts,
        // Week 12+ - a real order now needs a real logged-in buyer), so
        // this uses /v1/cart/quote instead - still permitAll, and
        // CartPricingService rejects a non-positive quantity with the
        // exact same ResponseStatusException(BAD_REQUEST) shape this test
        // has always meant to probe. Before the /error permitAll fix,
        // this came back 401 instead.
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
            [{"listingId":"00000000-0000-0000-0000-000000000000","quantity":0}]
            """;

        ResponseEntity<String> response =
            rest.postForEntity("/v1/cart/quote", new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
