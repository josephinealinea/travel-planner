package com.josephinealinea.planner.importer;

import com.josephinealinea.planner.budget.domain.BudgetItem;
import com.josephinealinea.planner.budget.domain.SettlementPayment;
import com.josephinealinea.planner.budget.infra.BudgetRepository;
import com.josephinealinea.planner.budget.infra.SettlementPaymentRepository;
import com.josephinealinea.planner.checklist.domain.ChecklistItem;
import com.josephinealinea.planner.checklist.infra.ChecklistRepository;
import com.josephinealinea.planner.destinations.domain.Destination;
import com.josephinealinea.planner.destinations.infra.DestinationRepository;
import com.josephinealinea.planner.identity.domain.User;
import com.josephinealinea.planner.identity.infra.UserRepository;
import com.josephinealinea.planner.identity.infra.YamlUserRepository;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.itinerary.infra.ItineraryRepository;
import com.josephinealinea.planner.publish.domain.PublishRequest;
import com.josephinealinea.planner.rates.domain.RateTable;
import com.josephinealinea.planner.storage.jdbc.JdbcValues;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.trips.domain.TripMember;
import com.josephinealinea.planner.trips.infra.TripRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Copies a YAML data directory into an empty PostgreSQL database, proves the
 * copy, and only then commits it.
 *
 * <h2>How</h2>
 * <ol>
 *   <li><b>One transaction around everything.</b> The JDBC repositories each
 *       open their own {@code TransactionTemplate}, which joins this one
 *       (propagation REQUIRED, same DataSource), so every row written below
 *       commits together or not at all. A failure anywhere — a constraint, a
 *       bug, a verification that does not pass — leaves the database exactly
 *       as empty as it was.</li>
 *   <li><b>Empty or nothing.</b> {@code users} and {@code trips} are locked
 *       against other writers first, then must both be empty. The importer
 *       never merges: two copies of one trip, or a YAML account overwriting a
 *       live one, would be far worse than a refusal.</li>
 *   <li><b>Read through the YAML repositories, written through the JDBC
 *       ones</b>, so every legacy upconversion the app does on read happens
 *       here too, and every mapping rule the JDBC repositories carry is
 *       applied — the import cannot drift from the app on either side.</li>
 *   <li><b>Users, then trips (with members and publish requests), then each
 *       trip's destinations, checklist, itinerary and budget</b> — the order
 *       the foreign keys need. Each trip's lists go in with {@code replaceAll}
 *       in file order, which is what gives {@code seq} the YAML's insertion
 *       order: budget rows tie-break on nothing else (F3).</li>
 *   <li><b>Timestamps are put back.</b> The user and trip repositories stamp
 *       {@code updatedAt} on every save (and {@code createdAt} when absent),
 *       as their YAML twins do. Right after each save, in the same
 *       transaction, an UPDATE restores the two values the file held —
 *       including a null — so the import does not rewrite everyone's history
 *       as "today". The per-trip repositories never stamp, by design.</li>
 *   <li><b>Verified inside the transaction</b> by {@link ImportVerifier}:
 *       every record field by field, and every member's budget summary and
 *       settlements recomputed from both stores. Any difference throws,
 *       which rolls everything back.</li>
 *   <li><b>A dry run</b> does all of the above and then marks the
 *       transaction rollback-only.</li>
 * </ol>
 *
 * Weather is not imported — it is a cache and refills on the first trip load
 * — and neither are exchange rates, which refresh on the first read that
 * finds them missing. Published pages are files, not rows, so they are not
 * imported either; the report lists the trips to publish again.
 */
public class YamlImporter {

    private final UserRepository users;
    private final TripRepository trips;
    private final DestinationRepository destinations;
    private final ChecklistRepository checklist;
    private final ItineraryRepository itinerary;
    private final BudgetRepository budget;
    private final SettlementPaymentRepository payments;
    private final JdbcClient jdbc;
    private final PlatformTransactionManager transactionManager;

    public YamlImporter(UserRepository users,
                        TripRepository trips,
                        DestinationRepository destinations,
                        ChecklistRepository checklist,
                        ItineraryRepository itinerary,
                        BudgetRepository budget,
                        SettlementPaymentRepository payments,
                        JdbcClient jdbc,
                        PlatformTransactionManager transactionManager) {
        this.users = users;
        this.trips = trips;
        this.destinations = destinations;
        this.checklist = checklist;
        this.itinerary = itinerary;
        this.budget = budget;
        this.payments = payments;
        this.jdbc = jdbc;
        this.transactionManager = transactionManager;
    }

    /** Thrown when nothing may be written at all. Rolls back, which here means nothing. */
    static final class Refused extends RuntimeException {
        Refused(String message) {
            super(message);
        }
    }

    /** Thrown when the copy does not match. The differences are in the report's verifier. */
    static final class VerificationFailed extends RuntimeException {
        VerificationFailed() {
            super("verification found differences (listed above), so nothing was committed");
        }
    }

    /** Never throws: every outcome, failure included, is in the report. */
    ImportReport run(Path dir, boolean dryRun) {
        YamlSource yaml = YamlSource.at(dir);
        ImportReport report = new ImportReport(yaml.dir, dryRun);

        if (!yaml.looksLikeDataDirectory()) {
            report.outcome = ImportReport.Outcome.REFUSED;
            report.failure = "no users.yml in " + yaml.dir + " — point app.import.yaml-dir at the data"
                    + " directory itself (the one holding users.yml and trips/)";
            return report;
        }

        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                requireEmptyDatabase();
                copy(yaml, report);
                verify(yaml, report);
                if (!report.verifier.passed()) throw new VerificationFailed();
                if (dryRun) status.setRollbackOnly();
            });
            report.outcome = dryRun ? ImportReport.Outcome.DRY_RUN : ImportReport.Outcome.COMMITTED;
        } catch (Refused e) {
            report.outcome = ImportReport.Outcome.REFUSED;
            report.failure = e.getMessage();
        } catch (VerificationFailed e) {
            report.outcome = ImportReport.Outcome.FAILED;
            report.failure = e.getMessage();
        } catch (RuntimeException e) {
            report.outcome = ImportReport.Outcome.FAILED;
            report.failure = describe(e);
            report.error = e;
        }
        return report;
    }

    // ---- guard --------------------------------------------------------------

    /**
     * Locks {@code users} and {@code trips} against every other writer until
     * this transaction ends — so nothing can sneak in between the check and
     * the import, a bootstrap owner included — then refuses unless both are
     * empty. Readers are not blocked.
     */
    private void requireEmptyDatabase() {
        jdbc.sql("LOCK TABLE users, trips IN EXCLUSIVE MODE").update();
        long userRows = jdbc.sql("SELECT count(*) FROM users").query(Long.class).single();
        long tripRows = jdbc.sql("SELECT count(*) FROM trips").query(Long.class).single();
        if (userRows > 0 || tripRows > 0) {
            throw new Refused("the database is not empty (" + userRows + " user(s), " + tripRows + " trip(s))."
                    + " The importer only fills an empty database and never merges into existing data."
                    + " If a start of the API created these — the bootstrap owner, from BOOTSTRAP_OWNER_EMAIL —"
                    + " and you are certain nothing in the database is needed, empty it"
                    + " (TRUNCATE users, trips CASCADE) and run the import again.");
        }
    }

    // ---- copy ---------------------------------------------------------------

    private void copy(YamlSource yaml, ImportReport report) {
        List<User> yamlUsers = yaml.users.findAll();
        List<Trip> yamlTrips = yaml.trips(report.warnings);
        requireConsistent(yamlUsers, yamlTrips);

        for (User user : yamlUsers) {
            Instant createdAt = user.getCreatedAt();
            Instant updatedAt = user.getUpdatedAt();
            users.save(user);
            restoreTimestamps("users", user.getId(), createdAt, updatedAt);
        }
        report.count("users", yamlUsers.size());

        int members = 0;
        int requests = 0;
        int droppedRateTables = 0;
        for (Trip trip : yamlTrips) {
            Instant createdAt = trip.getCreatedAt();
            Instant updatedAt = trip.getUpdatedAt();
            if (!trip.getExchangeRates().isEmpty()) droppedRateTables++;
            if (trip.isPublished()) {
                report.published.add(new ImportReport.PublishedTrip(trip.getSlug(), trip.getTitle(),
                        trip.getPublishedAt() == null ? null : trip.getPublishedAt().toString()));
            }
            for (PublishRequest request : trip.getPublishRequests()) {
                if (request.isPending()) {
                    report.pendingRequests.add(trip.getTitle() + " (" + trip.getSlug() + "), requested by "
                            + request.getRequestedByUserId());
                }
            }
            members += trip.getMembers().size();
            requests += trip.getPublishRequests().size();

            trips.save(trip);
            restoreTimestamps("trips", trip.getId(), createdAt, updatedAt);
        }
        report.count("trips", yamlTrips.size());
        report.count("trip members", members);
        report.count("publish requests", requests);

        int legacyLinks = 0;
        int legacyUnlinked = 0;
        int weatherRows = 0;
        for (Trip trip : yamlTrips) {
            String slug = trip.getSlug();

            List<Destination> destinationRows = yaml.destinations.findAll(slug);
            destinations.replaceAll(slug, destinationRows);
            report.count("destinations", destinationRows.size());

            List<ChecklistItem> checklistRows = yaml.checklist.findAll(slug);
            checklist.replaceAll(slug, checklistRows);
            report.count("checklist items", checklistRows.size());

            List<ItineraryItem> itineraryRows = yaml.itinerary.findAll(slug);
            itinerary.replaceAll(slug, itineraryRows);
            report.count("itinerary entries", itineraryRows.size());

            List<BudgetItem> budgetRows = yaml.budget.findAll(slug);
            budget.replaceAll(slug, budgetRows);
            report.count("budget rows", budgetRows.size());

            List<SettlementPayment> paymentRows = yaml.payments.findAll(slug);
            payments.replaceAll(slug, paymentRows);
            report.count("settlement payments", paymentRows.size());

            for (ChecklistItem row : checklistRows) {
                if (!row.legacyDestinationIds().isEmpty()) {
                    legacyLinks++;
                    if (row.getCountryCodes().isEmpty()) legacyUnlinked++;
                }
            }
            for (ItineraryItem row : itineraryRows) {
                if (!row.legacyDestinationIds().isEmpty()) {
                    legacyLinks++;
                    if (row.getCountryCodes().isEmpty()) legacyUnlinked++;
                }
            }
            for (BudgetItem row : budgetRows) {
                if (!row.legacyDestinationIds().isEmpty()) {
                    legacyLinks++;
                    if (row.getCountryCodes().isEmpty()) legacyUnlinked++;
                }
            }
            weatherRows += yaml.weather.findAll(slug).size();
        }

        report.skipped.put("weather readings", weatherRows);
        report.skipped.put("exchange rates", yaml.rates.load().getRates().size());
        report.notes.add("Weather is a cache and refills on the first trip load; exchange rates refresh on"
                + " the first read that finds none.");
        if (droppedRateTables > 0) {
            report.notes.add(droppedRateTables + " trip(s) carried an old per-trip exchange-rate table."
                    + " It has not been read since rates became install-wide, and is not stored.");
        }
        if (legacyLinks > 0) {
            report.notes.add(legacyLinks + " row(s) still carry a pre-country destinationId(s) link"
                    + " (" + legacyUnlinked + " of them with no countryCodes). YAML mode does not read it"
                    + " either, so each row imports exactly as the app shows it today; the old ids are not"
                    + " stored.");
        }
    }

    /**
     * What the foreign keys and unique constraints would reject anyway, said
     * in terms of the files — a constraint name is a poor way to learn that
     * users.yml is missing somebody.
     */
    private static void requireConsistent(List<User> yamlUsers, List<Trip> yamlTrips) {
        Set<String> userIds = new HashSet<>();
        Map<String, String> byEmail = new HashMap<>();
        for (User user : yamlUsers) {
            if (user.getId() == null || !userIds.add(user.getId())) {
                throw new IllegalStateException("users.yml: user id " + user.getId() + " is missing or repeated");
            }
            String email = YamlUserRepository.normalise(user.getEmail());
            String other = email == null ? null : byEmail.putIfAbsent(email, user.getId());
            if (email == null || other != null) {
                throw new IllegalStateException("users.yml: users " + other + " and " + user.getId()
                        + " share the email " + email + " (or it is missing); PostgreSQL requires it unique");
            }
        }
        Set<String> slugs = new HashSet<>();
        Set<String> tripIds = new HashSet<>();
        for (Trip trip : yamlTrips) {
            String where = "trip " + trip.getSlug();
            if (!slugs.add(trip.getSlug()) || !tripIds.add(trip.getId())) {
                throw new IllegalStateException(where + ": slug or id " + trip.getId() + " appears twice");
            }
            if (trip.getOwnerUserId() != null && !userIds.contains(trip.getOwnerUserId())) {
                throw new IllegalStateException(where + ": owner " + trip.getOwnerUserId() + " is not in users.yml");
            }
            Set<String> seen = new HashSet<>();
            for (TripMember member : trip.getMembers()) {
                if (!userIds.contains(member.getUserId())) {
                    throw new IllegalStateException(where + ": member " + member.getUserId() + " is not in users.yml");
                }
                if (!seen.add(member.getUserId())) {
                    throw new IllegalStateException(where + ": member " + member.getUserId() + " is listed twice");
                }
            }
        }
    }

    private void restoreTimestamps(String table, String id, Instant createdAt, Instant updatedAt) {
        int rows = jdbc.sql("UPDATE " + table + " SET created_at = :createdAt, updated_at = :updatedAt WHERE id = :id")
                .param("createdAt", JdbcValues.timestamptz(createdAt))
                .param("updatedAt", JdbcValues.timestamptz(updatedAt))
                .param("id", id)
                .update();
        if (rows != 1) {
            throw new IllegalStateException("restoring the timestamps of " + table + " " + id
                    + " touched " + rows + " rows, expected 1");
        }
    }

    // ---- verify -------------------------------------------------------------

    /**
     * Everything is re-read here — from disk on one side, through the JDBC
     * repositories on the other — rather than compared against the objects
     * just written: those were handed to the repositories, which may have
     * changed them (the timestamp stamping above is exactly such a change).
     */
    private void verify(YamlSource yaml, ImportReport report) {
        ImportVerifier verifier = new ImportVerifier();
        report.verifier = verifier;

        verifier.compareUsers(yaml.users.findAll(), users.findAll());

        List<Trip> yamlTrips = yaml.trips(new ArrayList<>());
        for (Trip trip : yamlTrips) {
            String slug = trip.getSlug();
            verifier.compareTrip(trip, trips.findBySlug(slug));
            verifier.compareRows("destinations", slug, yaml.destinations.findAll(slug), destinations.findAll(slug));
            verifier.compareRows("checklist", slug, yaml.checklist.findAll(slug), checklist.findAll(slug));
            verifier.compareRows("itinerary", slug, yaml.itinerary.findAll(slug), itinerary.findAll(slug));
            verifier.compareRows("budget", slug, yaml.budget.findAll(slug), budget.findAll(slug));
            verifier.compareRows("settlement payments", slug,
                    yaml.payments.findAll(slug), payments.findAll(slug));
        }

        // Nothing filed anywhere else: every table holds exactly what was
        // counted in, so no row can have landed under the wrong trip.
        requireTableCount(verifier, "trips", yamlTrips.size());
        requireTableCount(verifier, "trip_members", report.counts.getOrDefault("trip members", 0));
        requireTableCount(verifier, "publish_requests", report.counts.getOrDefault("publish requests", 0));
        requireTableCount(verifier, "destinations", report.counts.getOrDefault("destinations", 0));
        requireTableCount(verifier, "checklist_items", report.counts.getOrDefault("checklist items", 0));
        requireTableCount(verifier, "itinerary_items", report.counts.getOrDefault("itinerary entries", 0));
        requireTableCount(verifier, "budget_items", report.counts.getOrDefault("budget rows", 0));
        requireTableCount(verifier, "settlement_payments",
                report.counts.getOrDefault("settlement payments", 0));

        RateTable rates = yaml.rates.load();
        if (rates.isEmpty()) {
            report.notes.add("No rates.yml in the YAML directory, so the budget summaries were compared with"
                    + " no exchange rates: shares, settlements and native totals are compared exactly; a"
                    + " converted total reads as a missing rate on both sides.");
        } else {
            report.notes.add("Budget summaries were compared using the YAML directory's own rates.yml ("
                    + rates.getBase() + ", " + rates.getDate() + "), the same table for both sides.");
        }
        verifier.compareMoney(
                new ImportVerifier.Store(yaml.trips, yaml.users, yaml.destinations, yaml.checklist,
                        yaml.itinerary, yaml.budget, yaml.payments),
                new ImportVerifier.Store(trips, users, destinations, checklist, itinerary, budget, payments),
                rates, yamlTrips);

        if (verifier.emailsNormalised() > 0) {
            report.notes.add(verifier.emailsNormalised() + " email address(es) were stored lower-cased and"
                    + " trimmed, as every save of a user does; sign-in already looked them up that way.");
        }
    }

    private void requireTableCount(ImportVerifier verifier, String table, int expected) {
        long actual = jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
        if (actual != expected) {
            verifier.entities().differ(table + ": " + expected + " rows imported, but the table holds " + actual);
        }
    }

    private static String describe(Throwable e) {
        StringBuilder message = new StringBuilder(e.toString());
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            message.append("\n        caused by ").append(cause);
            if (cause.getCause() == cause) break;
        }
        return message.toString();
    }
}
