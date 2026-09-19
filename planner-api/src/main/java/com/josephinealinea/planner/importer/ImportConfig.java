package com.josephinealinea.planner.importer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link ImportProperties} and says how an import run ends.
 *
 * The import is the application itself started with
 * {@code --app.import.yaml-dir=…}; it has to stop once the import is done, or
 * "run the importer" would quietly turn into "start a second server". Ending
 * is a bean of its own so a test can watch the exit code instead of having
 * its JVM stopped underneath it.
 */
@Configuration
@EnableConfigurationProperties(ImportProperties.class)
public class ImportConfig {

    /** How an import run leaves: closes the context, then the JVM, with the given code. */
    @FunctionalInterface
    public interface ImportExit {
        void exit(int code);
    }

    @Bean
    ImportExit importExit(ApplicationContext context) {
        return code -> System.exit(SpringApplication.exit(context, () -> code));
    }
}
