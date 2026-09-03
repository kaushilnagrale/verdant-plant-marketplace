plugins { `java-library` }

dependencies {
    api(rootProject.libs.testcontainers.postgres)
    api(rootProject.libs.testcontainers.junit)
    api("org.springframework.boot:spring-boot-starter-test")
    api("org.springframework:spring-jdbc")
}
