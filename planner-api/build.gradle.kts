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

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Records + @RequestParam/@PathVariable name inference without explicit values.
tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
}
