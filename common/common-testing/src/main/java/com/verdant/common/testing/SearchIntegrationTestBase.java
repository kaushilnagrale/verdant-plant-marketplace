package com.verdant.common.testing;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.redpanda.RedpandaContainer;
import org.testcontainers.utility.DockerImageName;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Search-specific tests (the projection pipeline, ranking, the consistency
 * boundary - Week 9) need a real Kafka-protocol broker and a real
 * OpenSearch instance on top of everything IntegrationTestBase already
 * provides. Kept as a separate subclass rather than adding these
 * containers to IntegrationTestBase itself, so every OTHER test in this
 * project - the large majority, which touch neither - doesn't pay to
 * start two more containers it never uses.
 *
 * RedpandaContainer (a real, dedicated Testcontainers module) rather than
 * a GenericContainer: it correctly configures the broker's
 * advertised-listener address for a client connecting from outside the
 * container, a well-known footgun for any Kafka-protocol broker under
 * Testcontainers - the exact class of problem GenericContainer left the
 * caller to solve by hand for Redis (fine there, since Redis has no
 * advertised-address concept at all).
 *
 * OpenSearch has no dedicated Testcontainers module in 2.0.5
 * (testcontainers-opensearch does not exist) - same GenericContainer
 * treatment IntegrationTestBase already gives Redis, configured with the
 * same real settings docker-compose.yml uses for local dev (single-node,
 * security plugin disabled), just a smaller heap since tests already run
 * several containers at once.
 */
// Adds "search-test" on top of IntegrationTestBase's own "test"
// (@ActiveProfiles merges with the superclass by default, not replaces
// it) - a real broker exists here, unlike every other test in this
// project, so KafkaEventPublisher/StubEventPublisher's own @Profile
// expressions key off this second profile specifically to swap which one
// is active only for search tests, not project-wide.
@ActiveProfiles("search-test")
public abstract class SearchIntegrationTestBase extends IntegrationTestBase {

    protected static final RedpandaContainer REDPANDA =
        new RedpandaContainer(DockerImageName.parse("redpandadata/redpanda:v24.2.18"))
            .withReuse(true);

    protected static final GenericContainer<?> OPENSEARCH =
        new GenericContainer<>(DockerImageName.parse("opensearchproject/opensearch:2.18.0"))
            .withEnv("discovery.type", "single-node")
            .withEnv("DISABLE_SECURITY_PLUGIN", "true")
            .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(9200)
            .withReuse(true);

    static {
        REDPANDA.start();
        OPENSEARCH.start();
        createListingsIndex();
    }

    @DynamicPropertySource
    static void searchProps(DynamicPropertyRegistry r) {
        r.add("spring.kafka.bootstrap-servers", REDPANDA::getBootstrapServers);
        r.add("verdant.search.opensearch-uri", SearchIntegrationTestBase::opensearchBaseUri);
        // application-test.yml defaults this to false, protecting every
        // OTHER test in the project from ListingProjector's @KafkaListener
        // trying to connect to a nonexistent broker (see that class's own
        // doc comment). Search tests are exactly the ones with a real
        // broker to talk to, so it's turned back on here.
        r.add("verdant.search.projector.autostart", () -> "true");
    }

    /** protected, not private: Day 52's backfill test needs to PUT/DELETE its own extra, throwaway index directly, the same way this class creates "listings-v1" below. */
    protected static String opensearchBaseUri() {
        return "http://" + OPENSEARCH.getHost() + ":" + OPENSEARCH.getMappedPort(9200);
    }

    /**
     * A real deployment runs Day 48's own curl commands (PUT the index,
     * add the alias) once, by hand, before the app ever starts - nothing
     * in the application itself creates this index. A fresh test
     * container starts with neither, and OpenSearch's real behavior for
     * a GET against a genuinely nonexistent index is to throw
     * (index_not_found_exception), not return found=false - the tests
     * would fail confusingly on that exception rather than on their own
     * actual assertions. Reproduces Day 48's setup here instead of
     * skipping it, with the identical mapping file (copied into this
     * module's own resources - see that file's own header comment for
     * the sync-point this creates).
     */
    private static void createListingsIndex() {
        try {
            HttpClient http = HttpClient.newHttpClient();
            String mapping;
            try (InputStream in = SearchIntegrationTestBase.class.getResourceAsStream("/opensearch/listings-index.json")) {
                mapping = new String(in.readAllBytes());
            }

            http.send(HttpRequest.newBuilder(URI.create(opensearchBaseUri() + "/listings-v1"))
                .PUT(HttpRequest.BodyPublishers.ofString(mapping))
                .header("Content-Type", "application/json")
                .build(), HttpResponse.BodyHandlers.discarding());

            http.send(HttpRequest.newBuilder(URI.create(opensearchBaseUri() + "/_aliases"))
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"actions\":[{\"add\":{\"index\":\"listings-v1\",\"alias\":\"listings\"}}]}"))
                .header("Content-Type", "application/json")
                .build(), HttpResponse.BodyHandlers.discarding());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Failed to set up the listings index for search tests", e);
        }
    }
}
