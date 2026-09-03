package com.verdant.common.testing;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@ActiveProfiles("test")
@ExtendWith({LedgerInvariantExtension.class, AllocationFixtureCleanupExtension.class})
public abstract class IntegrationTestBase {

    protected static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16")
            .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("verdant")
            .withUsername("verdant")
            .withPassword("verdant")
            .withReuse(true);

    // Redis has no dedicated Testcontainers module as of 2.0.5 (dropped a
    // while back) - a plain GenericContainer against the same redis:7-alpine
    // image infra/docker/docker-compose.yml runs locally is the standard
    // approach.
    protected static final GenericContainer<?> REDIS =
        new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withReuse(true);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
