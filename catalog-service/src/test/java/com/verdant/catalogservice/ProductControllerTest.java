package com.verdant.catalogservice;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Deliberately self-contained rather than extending :app's own
 * IntegrationTestBase: a real extracted service's own tests shouldn't
 * need the monolith's full 39-migration history or its Spring context
 * to prove ITS OWN thing, which is narrower and more important here -
 * that a role with ONLY select/insert/update/delete on
 * canonical_products/listings (a small, self-contained stand-in for
 * V39__service_isolation.sql's own real grant, re-created fresh here
 * rather than depending on :app's migrations) is sufficient to run this
 * whole real application end to end, boot included. :app's own
 * ServiceIsolationTest separately proves the real V39 grants match the
 * real full schema; this proves the service built on top of that
 * pattern actually works.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ProductControllerTest {

    private static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("verdant_catalog_test")
            .withUsername("verdant")
            .withPassword("verdant");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", () -> "catalog_service_role");
        r.add("spring.datasource.password", () -> "test-password");
    }

    @BeforeAll
    static void seedSchemaAndRole() throws Exception {
        try (Connection admin = DriverManager.getConnection(
                 POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = admin.createStatement()) {
            st.execute("""
                CREATE TABLE canonical_products (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    display_name TEXT NOT NULL,
                    botanical_name TEXT NOT NULL
                )
                """);
            st.execute("""
                CREATE TABLE listings (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    canonical_product_id UUID REFERENCES canonical_products(id)
                )
                """);
            st.execute("CREATE ROLE catalog_service_role LOGIN PASSWORD 'test-password'");
            st.execute("GRANT USAGE ON SCHEMA public TO catalog_service_role");
            st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON canonical_products, listings TO catalog_service_role");
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void getProduct_returnsARealRowReadByTheRestrictedRole() throws Exception {
        UUID id = jdbc.queryForObject(
            "INSERT INTO canonical_products (display_name, botanical_name) VALUES ('Money Plant', 'Epipremnum aureum') RETURNING id",
            UUID.class);
        jdbc.update("INSERT INTO listings (canonical_product_id) VALUES (?)", id);

        mockMvc.perform(get("/v1/catalog-service/products/" + id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.displayName").value("Money Plant"))
            .andExpect(jsonPath("$.listingCount").value(1));
    }

    @Test
    void getProduct_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/v1/catalog-service/products/" + UUID.randomUUID()))
            .andExpect(status().isNotFound());
    }

    @Test
    void search_findsByPartialDisplayName() throws Exception {
        jdbc.update("INSERT INTO canonical_products (display_name, botanical_name) VALUES ('Snake Plant', 'Dracaena trifasciata')");

        mockMvc.perform(get("/v1/catalog-service/products").param("q", "snake"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.displayName == 'Snake Plant')]").exists());
    }
}
