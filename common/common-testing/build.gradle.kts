plugins { `java-library` }

dependencies {
    api(rootProject.libs.testcontainers.postgres)
    api(rootProject.libs.testcontainers.junit)
    // Redpanda has a dedicated Testcontainers module - real value, not
    // just convenience: it correctly handles the broker's
    // advertised-listener address, a well-known GenericContainer footgun
    // for any Kafka-protocol broker. OpenSearch has no dedicated module in
    // 2.0.5 (testcontainers-opensearch does not exist) - same situation as
    // Redis already handles in IntegrationTestBase, so OpenSearch also
    // uses a plain GenericContainer.
    api("org.testcontainers:testcontainers-redpanda")
    api("org.springframework.boot:spring-boot-starter-test")
    api("org.springframework:spring-jdbc")
}
