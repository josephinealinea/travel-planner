package com.josephinealinea.planner.importer;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.josephinealinea.planner.budget.api.BudgetService;
import com.josephinealinea.planner.budget.api.SettlementProperties;
import com.josephinealinea.planner.budget.infra.BudgetRepository;
import com.josephinealinea.planner.budget.infra.SettlementPaymentRepository;
import com.josephinealinea.planner.checklist.infra.ChecklistRepository;
import com.josephinealinea.planner.destinations.api.TripCountries;
import com.josephinealinea.planner.destinations.infra.DestinationRepository;
import com.josephinealinea.planner.identity.domain.User;
import com.josephinealinea.planner.identity.infra.UserRepository;
import com.josephinealinea.planner.identity.infra.YamlUserRepository;
import com.josephinealinea.planner.itinerary.infra.ItineraryRepository;
import com.josephinealinea.planner.rates.api.RatesService;
import com.josephinealinea.planner.rates.domain.RateTable;
import com.josephinealinea.planner.storage.YamlStore;
import com.josephinealinea.planner.trips.api.TripAccessService;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.trips.domain.TripMember;
import com.josephinealinea.planner.trips.infra.TripRepository;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Proves an import, before it is allowed to commit: what PostgreSQL now holds
 * is what the YAML held, and the money comes out the same.
 *
 * <h2>1. Every record, field by field</h2>
 * Both sides are turned into a tree with the YAML store's own Jackson
 * configuration — so "every field" means exactly the properties that reach a
 * YAML file, and a field added to a domain class later is compared without
 * anybody having to remember to add it here — and the two trees are walked
 * side by side. Lists are compared <b>in order</b>: member order and budget
 * insertion order are money (F3, F4), and list order is what the whole
 * comparison is about. Numbers are compared as written, scale included, so
 * {@code 12.50} against {@code 12.5} would be reported rather than waved
 * through.
 *
 * Only three differences are allowed, each for a documented reason:
 * <ul>
 *   <li><b>{@code Trip.exchangeRates} is not stored.</b> It has not been read
 *       since rates became one table for the install; it exists only so old
 *       files load (see JdbcTripRepository). Removed from both sides.</li>
 *   <li><b>Instants are compared to the microsecond</b>, {@code timestamptz}'s
 *       precision. A YAML stamp written on Linux carries nanoseconds that a
 *       database cannot hold.</li>
 *   <li><b>A user's email is compared normalised.</b> Both user repositories
 *       normalise on save and look up by the normalised form, so a
 *       hand-edited address in capitals could not have signed in from YAML
 *       either. Each one this touches is counted in the report.</li>
 * </ul>
 * Transient legacy fields (the old {@code destinationId(s)} links) are not
 * properties Jackson writes, so they drop out of the comparison by
 * construction — the importer reports how many rows carried one.
 *
 * <h2>2. The money</h2>
 * For every trip, {@link BudgetService#summarise(Trip, User)} is run twice —
 * once over the YAML repositories and once over the JDBC ones, sharing one
 * fixed rate table — for every member and for the published-page view with
 * nobody signed in. The two {@code Summary} records must be identical:
 * charged and forecast breakdowns, category and country slices, native
 * totals, shares and settlements. This is the check that a lost sharer order
 * or a rounded cent cannot slip past, because it is the arithmetic the
 * Budget tab and Settle Expenses actually do.
 */
final class ImportVerifier {

    /** Enough to see what went wrong without burying the summary. */
    static final int MAX_REPORTED = 40;

    private final ObjectMapper trees = comparisonMapper();

    private final Result entities = new Result();
    private final Result money = new Result();
    private int emailsNormalised;

    static final class Result {
        int compared;
        final List<String> differences = new ArrayList<>();

        boolean passed() {
            return differences.isEmpty();
        }

        void differ(String line) {
            differences.add(line);
        }
    }

    Result entities() { return entities; }

    Result money() { return money; }

    int emailsNormalised() { return emailsNormalised; }

    boolean passed() {
        return entities.passed() && money.passed();
    }

    // ---- 1. records ---------------------------------------------------------

    void compareUsers(List<User> yaml, List<User> database) {
        Map<String, User> stored = new LinkedHashMap<>();
        database.forEach(user -> stored.put(user.getId(), user));
        if (yaml.size() != database.size()) {
            entities.differ("users: YAML has " + yaml.size() + ", PostgreSQL has " + database.size());
        }
        // Matched by id: the users table has no insertion order, and nothing
        // reads users in list order (see JdbcUserRepository).
        for (User user : yaml) {
            entities.compared++;
            User other = stored.remove(user.getId());
            String where = "user " + user.getId();
            if (other == null) {
                entities.differ(where + ": missing from PostgreSQL");
                continue;
            }
            ObjectNode expected = tree(user);
            String normalised = YamlUserRepository.normalise(user.getEmail());
            if (normalised != null && !normalised.equals(user.getEmail())) {
                expected.put("email", normalised);
                emailsNormalised++;
            }
            diff(where, expected, tree(other), entities);
        }
        stored.keySet().forEach(id -> entities.differ("user " + id + ": in PostgreSQL but not in YAML"));
    }

    void compareTrip(Trip yaml, Optional<Trip> database) {
        entities.compared++;
        String where = "trip " + yaml.getSlug();
        if (database.isEmpty()) {
            entities.differ(where + ": missing from PostgreSQL");
            return;
        }
        ObjectNode expected = tree(yaml);
        ObjectNode actual = tree(database.get());
        expected.remove("exchangeRates");
        actual.remove("exchangeRates");
        diff(where, expected, actual, entities);
    }

    /** One per-trip list, compared row by row in stored order. */
    <T> void compareRows(String entity, String slug, List<T> yaml, List<T> database) {
        String where = entity + "[" + slug + "]";
        if (yaml.size() != database.size()) {
            entities.differ(where + ": YAML has " + yaml.size() + " rows, PostgreSQL has " + database.size());
        }
        int shared = Math.min(yaml.size(), database.size());
        for (int i = 0; i < shared; i++) {
            entities.compared++;
            diff(where + "[" + i + "]", tree(yaml.get(i)), tree(database.get(i)), entities);
        }
    }

    // ---- 2. money -----------------------------------------------------------

    /** The repositories one {@link BudgetService} reads through. */
    record Store(TripRepository trips,
                 UserRepository users,
                 DestinationRepository destinations,
                 ChecklistRepository checklist,
                 ItineraryRepository itinerary,
                 BudgetRepository budget,
                 SettlementPaymentRepository payments) {

        BudgetService budgetService(RatesService rates) {
            return new BudgetService(budget, itinerary, destinations, users,
                    new TripAccessService(trips), new TripCountries(destinations), rates,
                    SettlementProperties.off(), payments);
        }
    }

    /**
     * A RatesService that only ever answers with the given table: no provider,
     * no refresh, no write. Both summaries must convert with the very same
     * numbers, or a rate that moved between the two calls would read as the
     * import having changed somebody's money.
     */
    static RatesService fixedRates(RateTable table) {
        return new RatesService(null, null) {
            @Override
            public RateTable current() {
                return table;
            }

            @Override
            public boolean refresh() {
                return false;
            }
        };
    }

    void compareMoney(Store yaml, Store database, RateTable table, List<Trip> yamlTrips) {
        RatesService rates = fixedRates(table);
        BudgetService fromYaml = yaml.budgetService(rates);
        BudgetService fromDatabase = database.budgetService(rates);

        for (Trip yamlTrip : yamlTrips) {
            Optional<Trip> databaseTrip = database.trips().findBySlug(yamlTrip.getSlug());
            if (databaseTrip.isEmpty()) {
                money.differ("trip " + yamlTrip.getSlug() + ": missing from PostgreSQL, no money compared");
                continue;
            }

            // Nobody signed in: the published page's figures.
            money.compared++;
            diff("budget[" + yamlTrip.getSlug() + "] as published",
                    tree(fromYaml.summarise(yamlTrip)),
                    tree(fromDatabase.summarise(databaseTrip.get())), money);

            for (TripMember member : yamlTrip.getMembers()) {
                money.compared++;
                String where = "budget[" + yamlTrip.getSlug() + "] for " + member.getUserId();
                User yamlUser = yaml.users().findById(member.getUserId()).orElse(null);
                User databaseUser = database.users().findById(member.getUserId()).orElse(null);
                if (yamlUser == null || databaseUser == null) {
                    money.differ(where + ": no such user in " + (yamlUser == null ? "YAML" : "PostgreSQL"));
                    continue;
                }
                diff(where, tree(fromYaml.summarise(yamlTrip, yamlUser)),
                        tree(fromDatabase.summarise(databaseTrip.get(), databaseUser)), money);
            }
        }
    }

    // ---- trees --------------------------------------------------------------

    private ObjectNode tree(Object value) {
        return trees.valueToTree(value);
    }

    /**
     * The YAML store's own mapper — the same property set, inclusion and date
     * handling as the files — with two changes for comparing: instants are
     * written to the microsecond, and decimals keep their scale.
     */
    static ObjectMapper comparisonMapper() {
        ObjectMapper mapper = new YamlStore().mapper().copy();
        SimpleModule micros = new SimpleModule("import-instants-to-micros");
        micros.addSerializer(Instant.class, new StdSerializer<>(Instant.class) {
            @Override
            public void serialize(Instant value, JsonGenerator gen, SerializerProvider provider) throws IOException {
                gen.writeString(value.truncatedTo(ChronoUnit.MICROS).toString());
            }
        });
        mapper.registerModule(micros);
        // Without both of these a tree turns 12.50 into 12.5, and a changed
        // scale would compare equal.
        mapper.setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
        mapper.configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);
        return mapper;
    }

    /**
     * Walks two trees side by side and records every leaf that differs, by
     * path — {@code budget[latam][3].sharedByUserIds[0]: YAML "u-sam",
     * PostgreSQL "u-alex"} — so a failed import says exactly what it would
     * have changed.
     */
    static void diff(String path, JsonNode yaml, JsonNode database, Result into) {
        if (yaml == null || yaml.isMissingNode() || yaml.isNull()) {
            if (!(database == null || database.isMissingNode() || database.isNull())) {
                into.differ(path + ": absent in YAML, PostgreSQL " + database);
            }
            return;
        }
        if (database == null || database.isMissingNode() || database.isNull()) {
            into.differ(path + ": YAML " + yaml + ", absent in PostgreSQL");
            return;
        }
        if (yaml.isObject() && database.isObject()) {
            Set<String> names = new LinkedHashSet<>();
            yaml.fieldNames().forEachRemaining(names::add);
            database.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                diff(path + "." + name, yaml.get(name), database.get(name), into);
            }
            return;
        }
        if (yaml.isArray() && database.isArray()) {
            if (yaml.size() != database.size()) {
                into.differ(path + ": YAML has " + yaml.size() + " entries " + yaml
                        + ", PostgreSQL has " + database.size() + " " + database);
                return;
            }
            Iterator<JsonNode> left = yaml.elements();
            Iterator<JsonNode> right = database.elements();
            for (int i = 0; left.hasNext(); i++) {
                diff(path + "[" + i + "]", left.next(), right.next(), into);
            }
            return;
        }
        // Leaves: the same kind of value, written the same way. asText keeps a
        // decimal's scale and a double's every digit.
        if (yaml.getNodeType() != database.getNodeType() || !yaml.asText().equals(database.asText())) {
            into.differ(path + ": YAML " + yaml + ", PostgreSQL " + database);
        }
    }
}
