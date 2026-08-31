plugins { alias(libs.plugins.springBoot) }

dependencies {
    implementation(project(":common:common-domain"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-aspectj")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation(rootProject.libs.flyway.postgres)
    runtimeOnly(rootProject.libs.postgresql)

    testImplementation(project(":common:common-testing"))
    testImplementation(rootProject.libs.archunit)
    testImplementation(rootProject.libs.jqwik)
    testImplementation("org.springframework.security:spring-security-test")
}
