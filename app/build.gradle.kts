plugins { alias(libs.plugins.springBoot) }

dependencies {
    implementation(project(":common:common-domain"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Actuator's /actuator/metrics is Spring's own JSON shape - a real
    // Prometheus server (Week 12 observability) scrapes text-format
    // /actuator/prometheus, which needs this concrete registry
    // implementation on the classpath; the exposure list alone (below)
    // does nothing without it.
    implementation("io.micrometer:micrometer-registry-prometheus")
    // Distributed tracing (Week 12). REAL BUG, found by tracing never
    // reaching Jaeger despite these two alone: Spring Boot 4.x split
    // spring-boot-starter-actuator's old monolithic autoconfiguration
    // jar into many fine-grained modules (spring-boot-micrometer-metrics,
    // spring-boot-health, ... - confirmed by listing what's actually on
    // this project's own classpath, since spring-boot-actuator-
    // autoconfigure-4.1.1.jar itself now contains zero tracing/otlp/
    // zipkin classes at all). micrometer-tracing-bridge-otel and
    // opentelemetry-exporter-otlp supply the raw bridge/exporter
    // classes, but nothing actually CONSTRUCTS and registers their
    // beans from management.tracing/management.otlp.tracing without
    // this additional, separate autoconfiguration module - confirmed by
    // grepping spring-boot-dependencies' own real BOM for "tracing".
    implementation("org.springframework.boot:spring-boot-micrometer-tracing-opentelemetry")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    implementation("org.springframework.boot:spring-boot-starter-aspectj")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation(rootProject.libs.flyway.postgres)
    runtimeOnly(rootProject.libs.postgresql)

    implementation(platform("software.amazon.awssdk:bom:2.46.7"))
    implementation("software.amazon.awssdk:s3")
    implementation("net.coobird:thumbnailator:0.4.20")
    implementation("com.ibm.icu:icu4j:77.1")
    implementation("org.apache.commons:commons-text:1.15.0")

    implementation(platform("org.springframework.ai:spring-ai-bom:2.0.1"))
    implementation("org.springframework.ai:spring-ai-starter-model-transformers")

    implementation("com.razorpay:razorpay-java:1.4.8")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // Matches the OpenSearch server version (2.18.0, docker-compose.yml) -
    // pinning the client to the server's own version is the safest default
    // absent a specific reason to diverge. opensearch-java is the fluent
    // API surface only - it pulls in no HTTP transport of its own, hence
    // opensearch-rest-client (RestClientTransport) alongside it.
    //
    // REAL BUG, found by an entirely unrelated, unmodified test
    // (OutboxTest) suddenly failing: opensearch-java transitively pulls in
    // org.eclipse.parsson (a Jakarta JSON-P implementation) via
    // jakarta.json-api, and Hibernate's own JSON column support
    // (@JdbcTypeCode(SqlTypes.JSON), OutboxEvent.payload, Week 6)
    // auto-detects which FormatMapper to use from whatever JSON libraries
    // are on the classpath - a second one being newly present made it
    // pick Jakarta JSON-P over Jackson, and it has no idea how to
    // (de)serialize tools.jackson.databind.JsonNode.
    //
    // Excluding parsson was the first fix tried and it does NOT work:
    // opensearch-java does its own eager jakarta.json.spi.JsonProvider
    // SPI lookup at OpenSearchClient construction time regardless of
    // which JsonpMapper is actually passed in (confirmed - excluding it
    // broke bean creation outright with "Provider ...JsonProviderImpl not
    // found"). parsson has to stay. The real fix is in application.yml:
    // force Hibernate's json_format_mapper to jackson explicitly rather
    // than leave it to an auto-detection that a second JSON library on
    // the classpath can silently redirect.
    implementation("org.opensearch.client:opensearch-java:2.18.0")
    implementation("org.opensearch.client:opensearch-rest-client:2.18.0")

    testImplementation(project(":common:common-testing"))
    testImplementation(rootProject.libs.archunit)
    testImplementation(rootProject.libs.jqwik)
    testImplementation("net.jqwik:jqwik-spring:0.12.0")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-restclient")
    testImplementation("org.springframework.boot:spring-boot-resttestclient")
}
