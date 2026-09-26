package com.josephinealinea.planner.docs;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Keeps {@code docs/api/openapi.yaml} equal to what the controllers say.
 *
 * The spec is generated, never hand-written: springdoc reads the controllers
 * and this test compares the result with the committed file. Run it normally
 * and a stale file fails the build; run it with {@code -Dopenapi.update=true}
 * (or {@code ./gradlew openApiUpdate}) and it rewrites the file instead.
 *
 * springdoc is a test dependency only, so the running API serves no spec.
 * Filters are off because this reads the spec rather than exercising auth.
 */
@SpringBootTest(properties = "feature-enable-database=false")
@AutoConfigureMockMvc(addFilters = false)
class OpenApiDocTest {

    private static final Path SPEC = Path.of("..", "docs", "api", "openapi.yaml");

    @TempDir
    static Path data;

    @DynamicPropertySource
    static void yamlMode(DynamicPropertyRegistry registry) {
        registry.add("app.storage.root", () -> data.resolve("store").toString());
        registry.add("app.publish.dir", () -> data.resolve("published").toString());
        registry.add("app.rates.base-url", () -> "http://127.0.0.1:1");
        registry.add("app.mail.mode", () -> "log");
        registry.add("springdoc.api-docs.version", () -> "openapi_3_0");
    }

    @TestConfiguration
    static class SpecInfo {
        @Bean
        OpenAPI info() {
            return new OpenAPI().info(new Info()
                    .title("Travelling Llama planner API")
                    .description("Generated from the controllers by OpenApiDocTest. Do not edit by hand.")
                    .version("v1"));
        }
    }

    @Autowired
    MockMvc mvc;

    @Test
    void theCommittedSpecMatchesTheControllers() throws Exception {
        String generated = mvc.perform(get("/v3/api-docs.yaml"))
                .andReturn().getResponse().getContentAsString();
        assertThat(generated).contains("openapi:");

        if (Boolean.getBoolean("openapi.update")) {
            Files.writeString(SPEC, generated);
            return;
        }
        assertThat(Files.exists(SPEC)).as("docs/api/openapi.yaml is missing; run ./gradlew openApiUpdate").isTrue();
        assertThat(Files.readString(SPEC))
                .as("docs/api/openapi.yaml is stale; run ./gradlew openApiUpdate and commit it")
                .isEqualTo(generated);
    }
}
