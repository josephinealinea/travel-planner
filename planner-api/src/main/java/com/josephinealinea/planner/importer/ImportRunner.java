package com.josephinealinea.planner.importer;

import com.josephinealinea.planner.budget.infra.BudgetRepository;
import com.josephinealinea.planner.checklist.infra.ChecklistRepository;
import com.josephinealinea.planner.config.FeatureFlags;
import com.josephinealinea.planner.destinations.infra.DestinationRepository;
import com.josephinealinea.planner.identity.infra.UserRepository;
import com.josephinealinea.planner.itinerary.infra.ItineraryRepository;
import com.josephinealinea.planner.trips.infra.TripRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.PrintStream;
import java.nio.file.Path;

/**
 * The one-off import, run as the application itself:
 * {@code FEATURE_ENABLE_DATABASE=true ./gradlew bootRun
 * --args='--app.import.yaml-dir=./data'}. Does nothing at all unless
 * {@code app.import.yaml-dir} is set, which is every ordinary start.
 *
 * <h2>Before the bootstrap owner, and instead of it</h2>
 * {@code BootstrapOwner} is also an ApplicationRunner, and on an empty
 * database with {@code BOOTSTRAP_OWNER_EMAIL} set it would create a user —
 * after which the import, rightly, refuses a database that is not empty. So
 * this runner is ordered first ({@link Ordered#HIGHEST_PRECEDENCE}; the
 * bootstrap runner carries no order, which sorts last), and it ends the
 * process when it is done, so the bootstrap runner never runs on an import
 * start at all. Should anything ever create a user first regardless, the
 * import refuses rather than merging, and the refusal says why.
 *
 * <h2>Exit codes</h2>
 * 0 — imported and verified, or a dry run that verified. 1 — failed part-way
 * or a verification did not pass; everything was rolled back. 2 — refused
 * before writing anything: YAML mode, a directory with no users.yml, or a
 * database that already holds data.
 *
 * Nothing starts serving in between: the web server is up by the time
 * runners run, but the process exits as soon as this one returns, before the
 * application reports itself ready (which is also what keeps the startup
 * exchange-rate refresh from running).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ImportRunner implements ApplicationRunner {

    static final int SUCCESS = 0;
    static final int FAILED = 1;
    static final int REFUSED = 2;

    private final ImportProperties properties;
    private final FeatureFlags flags;
    private final ObjectProvider<UserRepository> users;
    private final ObjectProvider<TripRepository> trips;
    private final ObjectProvider<DestinationRepository> destinations;
    private final ObjectProvider<ChecklistRepository> checklist;
    private final ObjectProvider<ItineraryRepository> itinerary;
    private final ObjectProvider<BudgetRepository> budget;
    private final ObjectProvider<JdbcClient> jdbc;
    private final ObjectProvider<PlatformTransactionManager> transactionManager;
    private final ImportConfig.ImportExit exit;
    private final PrintStream out;

    public ImportRunner(ImportProperties properties,
                        FeatureFlags flags,
                        ObjectProvider<UserRepository> users,
                        ObjectProvider<TripRepository> trips,
                        ObjectProvider<DestinationRepository> destinations,
                        ObjectProvider<ChecklistRepository> checklist,
                        ObjectProvider<ItineraryRepository> itinerary,
                        ObjectProvider<BudgetRepository> budget,
                        ObjectProvider<JdbcClient> jdbc,
                        ObjectProvider<PlatformTransactionManager> transactionManager,
                        ImportConfig.ImportExit exit) {
        this.properties = properties;
        this.flags = flags;
        this.users = users;
        this.trips = trips;
        this.destinations = destinations;
        this.checklist = checklist;
        this.itinerary = itinerary;
        this.budget = budget;
        this.jdbc = jdbc;
        this.transactionManager = transactionManager;
        this.exit = exit;
        this.out = System.out;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.requested()) return;
        exit.exit(execute());
    }

    /** The import, start to finish, as an exit code. Prints its own report. */
    int execute() {
        if (!flags.databaseEnabled()) {
            // In YAML mode the repositories in the context are the YAML ones,
            // so "importing" would read the directory and write it back into
            // YAML. Refused before anything is touched.
            out.println();
            out.println("Import REFUSED — nothing was written.");
            out.println("Reason: app.import.yaml-dir is set but feature-enable-database is off. The import"
                    + " copies YAML into PostgreSQL, so it only runs in database mode: set"
                    + " FEATURE_ENABLE_DATABASE=true and DB_URL / DB_USER / DB_PASSWORD for the target"
                    + " database.");
            out.flush();
            return REFUSED;
        }

        YamlImporter importer = new YamlImporter(
                users.getObject(), trips.getObject(), destinations.getObject(), checklist.getObject(),
                itinerary.getObject(), budget.getObject(), jdbc.getObject(), transactionManager.getObject());
        ImportReport report = importer.run(Path.of(properties.yamlDir()), properties.dryRun());
        report.print(out);
        if (report.error != null) report.error.printStackTrace(out);

        return switch (report.outcome) {
            case COMMITTED, DRY_RUN -> SUCCESS;
            case REFUSED -> REFUSED;
            case FAILED -> FAILED;
        };
    }
}
