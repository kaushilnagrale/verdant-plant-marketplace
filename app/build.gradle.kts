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

    implementation(platform("software.amazon.awssdk:bom:2.46.7"))
    implementation("software.amazon.awssdk:s3")
    implementation("net.coobird:thumbnailator:0.4.20")
    implementation("com.ibm.icu:icu4j:77.1")
    implementation("org.apache.commons:commons-text:1.15.0")

    implementation(platform("org.springframework.ai:spring-ai-bom:2.0.1"))
    implementation("org.springframework.ai:spring-ai-starter-model-transformers")

    implementation("com.razorpay:razorpay-java:1.4.8")

    testImplementation(project(":common:common-testing"))
    testImplementation(rootProject.libs.archunit)
    testImplementation(rootProject.libs.jqwik)
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
}
