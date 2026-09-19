package com.josephinealinea.planner.importer;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The one-off YAML → PostgreSQL import: {@code app.import.*} in
 * application.yml ({@code IMPORT_YAML_DIR}, {@code IMPORT_DRY_RUN}).
 *
 * Its own record rather than a component of {@code AppProperties}, which is
 * constructed positionally across the test suite — see §5.11 of the Postgres
 * plan.
 *
 * @param yamlDir the data directory to import from — the one holding
 *                {@code users.yml} and {@code trips/index.yml}. Blank means
 *                "no import", which is every normal start of the app.
 * @param dryRun  import and verify everything, then roll back, so the run
 *                proves the import would work while writing nothing
 */
@ConfigurationProperties(prefix = "app.import")
public record ImportProperties(String yamlDir, boolean dryRun) {

    /** True when a directory was given, i.e. this start of the app is an import. */
    public boolean requested() {
        return yamlDir != null && !yamlDir.isBlank();
    }
}
