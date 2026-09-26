plugins {
    java
    id("org.springframework.boot") version "3.5.3"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.josephinealinea"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

// Boot 3.5.3's BOM pins Testcontainers 1.21.2, whose Docker client speaks API
// 1.32 — which Docker Engine 29 refuses ("minimum supported API version is
// 1.44"), so every database test was silently skipped as "no Docker". 1.21.4
// negotiates the version. Remove this once the Boot BOM carries ≥ 1.21.4.
extra["testcontainers.version"] = "1.21.4"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // JWT encode/decode via Nimbus, which ships with Spring Security's JOSE
    // support — no third-party JWT library.
    implementation("org.springframework.security:spring-security-oauth2-jose")

    // The whole persistence layer. Version is managed by the Boot BOM.
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // Only wired up when app.mail.mode=smtp.
    implementation("org.springframework.boot:spring-boot-starter-mail")

    // Database mode (feature-enable-database=true): PostgreSQL through plain
    // JdbcClient, schema owned by Flyway. With the flag off none of this is
    // auto-configured — DatabaseModeEnvironment excludes it — so YAML mode
    // starts exactly as it did before these lines existed.
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    // Real PostgreSQL for the JDBC repositories; skipped on a machine without Docker.
    // Versions come from the testcontainers.version pin above.
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    // MinIO stands in for Cloudflare R2 in the page-store contract test.
    testImplementation("org.testcontainers:minio")

    // Generates docs/api/openapi.yaml from the controllers. Test scope on purpose:
    // the running API never serves its own spec, and the image carries no
    // springdoc. See OpenApiDocTest.
    testImplementation("org.springdoc:springdoc-openapi-starter-webmvc-api:2.8.9")

    // Published pages on Cloudflare R2 (app.publish.store=r2), over its
    // S3-compatible API. Only the lightweight URL-connection HTTP client — the
    // default Netty and Apache ones (apache5 since 2.3x) are excluded so they
    // add nothing to the image or to a cold start.
    implementation(platform("software.amazon.awssdk:bom:2.55.0"))
    implementation("software.amazon.awssdk:s3") {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "apache5-client")
    }
    implementation("software.amazon.awssdk:url-connection-client")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Records + @RequestParam/@PathVariable name inference without explicit values.
tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
}

// Rewrites docs/api/openapi.yaml from the controllers. The same test, in update mode.
tasks.register<Test>("openApiUpdate") {
    description = "Regenerates docs/api/openapi.yaml from the controllers"
    group = "documentation"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("OpenApiDocTest") }
    systemProperty("openapi.update", "true")
    outputs.upToDateWhen { false }
}
