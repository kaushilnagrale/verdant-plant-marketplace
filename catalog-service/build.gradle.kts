// A genuinely separate, independently buildable/runnable Spring Boot
// module - not a package inside :app. Deliberately depends on neither
// :app nor :common:common-domain: a real extracted service shouldn't
// share a compile-time dependency on the monolith's own internal
// domain types (that coupling would defeat the point of the boundary).
// See DECISIONS.md and V39__service_isolation.sql for the real scope
// call this module represents - a proof-of-concept extraction proving
// the connection/role pattern for real, not a full data-ownership
// cutover of the whole catalog domain out of the monolith's own database.
plugins { alias(libs.plugins.springBoot) }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly(rootProject.libs.postgresql)

    testImplementation(rootProject.libs.testcontainers.postgres)
    testImplementation(rootProject.libs.testcontainers.junit)
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
}
