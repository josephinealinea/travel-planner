package com.josephinealinea.planner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * On Cloud Run every secret arrives as one mounted properties file
 * (secrets-bundle.sh writes it, deploy.sh mounts it and sets
 * SPRING_CONFIG_IMPORT). This proves the ${NAME:} placeholders in
 * application.yml resolve from such a file, including the one escape the
 * script writes (a backslash is doubled), and that a key the file leaves out
 * stays empty rather than failing the start.
 */
@SpringBootTest(properties = {
        "feature-enable-database=false",
        "spring.config.import=file:src/test/resources/secrets-bundle-sample.properties"})
class SecretsBundleStartupTest {

    @TempDir
    static Path data;

    @DynamicPropertySource
    static void yamlMode(DynamicPropertyRegistry registry) {
        registry.add("app.storage.root", () -> data.resolve("store").toString());
        registry.add("app.publish.dir", () -> data.resolve("published").toString());
        registry.add("app.rates.base-url", () -> "http://127.0.0.1:1");
        registry.add("app.mail.mode", () -> "log");
        registry.add("app.bootstrap.owner-email", () -> "owner@example.com");
        registry.add("app.bootstrap.owner-password", () -> "password123");
    }

    @Autowired
    Environment env;

    @Test
    void placeholdersResolveFromTheMountedFile() {
        assertThat(env.getProperty("app.security.jwt-secret")).isEqualTo("jwt+/value==with\\backslash-and-padding-to-be-long-enough-for-hs256");
        assertThat(env.getProperty("app.proxy.secret")).isEqualTo("proxy-sample");
        assertThat(env.getProperty("spring.datasource.password")).isEqualTo("db-sample");
        assertThat(env.getProperty("app.r2.secret-access-key")).isEqualTo("r2-sample");
    }

    @Test
    void aKeyTheFileLeavesOutIsEmpty() {
        assertThat(env.getProperty("spring.mail.password")).isEmpty();
    }
}
