# Travellers (Mine / Whole trip) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let destinations, checklist items and plans say which travel buddies they are for, inherited down the chain, and give every buddy a Mine / Whole trip view plus a personal published page limited to their parts.

**Architecture:** A nullable `travellerIds` on three domain records, where null means "same as the parent". One pure resolver, `trips/api/Travellers`, works out who is on anything and what is "mine". The API ships `mine` id lists and display names; the page filters on them and never re-derives them. Costs copy the plan's travellers once, when their budget row is created, and never follow afterwards.

**Tech Stack:** Spring Boot 3.5 / Java 21, Jackson 2 YAML, PostgreSQL 17 via Flyway + JdbcClient, JUnit 5 + AssertJ + Testcontainers, Alpine.js (no bundler), Sass.

**Spec:** `.claude/plans/2026-09-19-trip-travellers-spec.md`. Read it first; this plan implements it section by section.

## Global Constraints

- **Do not commit, branch, tag or push.** The user writes their own git history. Each task ends with a checkpoint instead of a commit.
- **Three states, never collapsed:** `travellerIds` null = not set (follow the parent); `[]` = explicitly the whole trip; `[ids]` = those buddies. YAML: absent vs `travellerIds: []`. Postgres: `NULL` vs `'{}'`.
- **Empty resolved list means the whole trip.** Buddies who have left drop out, and an empty result is the whole trip.
- **Costs never follow.** A plan's travellers seed a new budget row's `sharedByUserIds` once. Later changes never touch it.
- **Not displayed means not shipped** on published pages. No traveller names or user ids ever reach a published file.
- **On screen the word is "travel buddy"; in code it is "member".**
- **Run tests with Docker reachable:** `cd planner-api && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock ./gradlew test`. After every full run, check `skipped=0` with:
  ```bash
  cat build/test-results/test/*.xml | grep -o '<testsuite [^>]*' | grep -o 'skipped="[0-9]*"' | sort | uniq -c
  ```
  The only line printed must be `skipped="0"`.
- **Browser checks use scratch data only.** Never `planner-api/data`. Start the demo API with `PORT=8080 PUBLIC_BASE_URL=http://localhost:8080/p DATA_DIR=<scratch>/data PUBLISH_DIR=<scratch>/data/published BOOTSTRAP_OWNER_EMAIL=demo@example.com BOOTSTRAP_OWNER_PASSWORD=llama-demo-123 ./gradlew bootRun`, and open the site with `?api=http://localhost:8080`.

---

### Task 1: Store `travellerIds` in both stores

**Files:**
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/destinations/domain/Destination.java`
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/checklist/domain/ChecklistItem.java`
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/itinerary/domain/ItineraryItem.java`
- Create: `planner-api/src/main/resources/db/migration/V2__travellers.sql`
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/storage/jdbc/JdbcValues.java`
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/destinations/infra/JdbcDestinationRepository.java`
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/checklist/infra/JdbcChecklistRepository.java`
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/itinerary/infra/JdbcItineraryRepository.java`
- Test: `planner-api/src/test/java/com/josephinealinea/planner/destinations/DestinationRepositoryContract.java`
- Test: `planner-api/src/test/java/com/josephinealinea/planner/checklist/ChecklistRepositoryContract.java`
- Test: `planner-api/src/test/java/com/josephinealinea/planner/itinerary/ItineraryRepositoryContract.java`

**Interfaces:**
- Produces: `List<String> getTravellerIds()` / `void setTravellerIds(List<String>)` on `Destination`, `ChecklistItem`, `ItineraryItem`. The getter returns null when not set. The setter keeps null as null and copies a list.
- Produces: `JdbcValues.nullableTextArray(List<String>)` (returns `Object`) and `JdbcValues.nullableTextList(ResultSet, String)` (returns `List<String>` or null).

- [ ] **Step 1: Add the failing contract tests (one per contract, same shape)**

In `DestinationRepositoryContract.java`, add after `falseIsNotTheSameAsAbsent`:

```java
    /**
     * "Not set" follows the parent and "[]" is explicitly the whole trip, so
     * the store must keep them apart. See trips.api.Travellers.
     */
    @Test
    void notSetAndWholeTripAreDifferentTravellerStates() {
        Destination unset = entity("unset", "Uyuni");
        Destination everyone = entity("everyone", "La Paz");
        everyone.setTravellerIds(java.util.List.of());
        Destination named = entity("named", "Cusco");
        named.setTravellerIds(java.util.List.of("user-sam", "user-alex"));

        store().save(TRIP, unset);
        store().save(TRIP, everyone);
        store().save(TRIP, named);

        assertThat(store().findById(TRIP, "unset").orElseThrow().getTravellerIds()).isNull();
        assertThat(store().findById(TRIP, "everyone").orElseThrow().getTravellerIds()).isEmpty();
        assertThat(store().findById(TRIP, "everyone").orElseThrow().getTravellerIds()).isNotNull();
        // Order is kept: it is the order the buddies were picked in.
        assertThat(store().findById(TRIP, "named").orElseThrow().getTravellerIds())
                .containsExactly("user-sam", "user-alex");
    }
```

In `fullyPopulated()` of the same file, add before `return d;`:

```java
        d.setTravellerIds(java.util.List.of("user-ana", "user-ben"));
```

Add the same test to `ChecklistRepositoryContract.java` (with `ChecklistItem` and `entity(id, label)`) and to `ItineraryRepositoryContract.java` (with `ItineraryItem`). Also add `item.setTravellerIds(List.of("user-ana", "user-ben"));` to each of their `fullyPopulated()` methods. The checklist contract's version:

```java
    @Test
    void notSetAndWholeTripAreDifferentTravellerStates() {
        ChecklistItem unset = entity("unset", "Book Uyuni tour");
        ChecklistItem everyone = entity("everyone", "Visas");
        everyone.setTravellerIds(List.of());
        ChecklistItem named = entity("named", "Cusco hotel");
        named.setTravellerIds(List.of("user-sam", "user-alex"));

        store().save(TRIP, unset);
        store().save(TRIP, everyone);
        store().save(TRIP, named);

        assertThat(store().findById(TRIP, "unset").orElseThrow().getTravellerIds()).isNull();
        assertThat(store().findById(TRIP, "everyone").orElseThrow().getTravellerIds()).isNotNull().isEmpty();
        assertThat(store().findById(TRIP, "named").orElseThrow().getTravellerIds())
                .containsExactly("user-sam", "user-alex");
    }
```

The itinerary contract's version:

```java
    @Test
    void notSetAndWholeTripAreDifferentTravellerStates() {
        ItineraryItem unset = entity("unset", "Bus to Uyuni");
        ItineraryItem everyone = entity("everyone", "Welcome dinner");
        everyone.setTravellerIds(List.of());
        ItineraryItem named = entity("named", "Cusco hotel");
        named.setTravellerIds(List.of("user-sam", "user-alex"));

        store().save(TRIP, unset);
        store().save(TRIP, everyone);
        store().save(TRIP, named);

        assertThat(store().findById(TRIP, "unset").orElseThrow().getTravellerIds()).isNull();
        assertThat(store().findById(TRIP, "everyone").orElseThrow().getTravellerIds()).isNotNull().isEmpty();
        assertThat(store().findById(TRIP, "named").orElseThrow().getTravellerIds())
                .containsExactly("user-sam", "user-alex");
    }
```

If either contract file lacks `import java.util.List;`, add it.

- [ ] **Step 2: Run the tests to see them fail**

Run: `cd planner-api && ./gradlew compileTestJava`
Expected: compilation fails with `cannot find symbol ... setTravellerIds`.

- [ ] **Step 3: Add the field to the three domain classes**

In each of `Destination`, `ChecklistItem` and `ItineraryItem`, add beside the other fields:

```java
    /**
     * Which travel buddies this is for. Three states, and the first two are
     * not the same: null is "not set, same as the parent" (for a destination
     * the parent is the whole trip); an empty list is explicitly the whole
     * trip; otherwise those member user ids, in the order picked. Resolved by
     * trips.api.Travellers; never read directly to decide who sees what.
     */
    private List<String> travellerIds;
```

and beside the other accessors:

```java
    public List<String> getTravellerIds() { return travellerIds; }
    public void setTravellerIds(List<String> travellerIds) {
        // Null stays null: it is a state ("follow the parent"), not an empty list.
        this.travellerIds = travellerIds == null ? null : new ArrayList<>(travellerIds);
    }
```

Add `import java.util.ArrayList;` and `import java.util.List;` to `Destination.java` if missing (the other two already import both).

- [ ] **Step 4: Run the YAML half**

Run: `cd planner-api && ./gradlew test --tests 'Yaml*RepositoryTest'`
Expected: PASS. YAML writes with `NON_NULL`, so null is absent and `[]` is written as `travellerIds: []`.

- [ ] **Step 5: Add the migration**

Create `planner-api/src/main/resources/db/migration/V2__travellers.sql`:

```sql
-- Who is going to each part of a trip. See trips.api.Travellers.
--
-- Nullable with no default, unlike budget_items.shared_by_user_ids: NULL means
-- "not set, same as the parent" and '{}' means "explicitly the whole trip".
-- Those are two different states, and a NOT NULL DEFAULT '{}' column would
-- silently turn every "follow Cusco" into "everyone".
ALTER TABLE destinations    ADD COLUMN traveller_ids text[];
ALTER TABLE checklist_items ADD COLUMN traveller_ids text[];
ALTER TABLE itinerary_items ADD COLUMN traveller_ids text[];
```

- [ ] **Step 6: Add the nullable array helpers**

In `JdbcValues.java`, add imports `org.springframework.jdbc.core.SqlParameterValue` and `java.sql.Types`, then add after `textArray`:

```java
    /**
     * A nullable {@code text[]} parameter. Null stays SQL NULL — for the
     * traveller columns that is a state ("follow the parent"), not an empty
     * list — while any list, even an empty one, becomes an array.
     */
    public static Object nullableTextArray(List<String> values) {
        return values == null ? new SqlParameterValue(Types.ARRAY, null) : textArray(values);
    }
```

and after `textList`:

```java
    /** A nullable {@code text[]} column: NULL reads as null, never as an empty list. */
    public static List<String> nullableTextList(ResultSet rs, String column) throws SQLException {
        java.sql.Array array = rs.getArray(column);
        if (array == null) return null;
        try {
            return new ArrayList<>(Arrays.asList((String[]) array.getArray()));
        } finally {
            array.free();
        }
    }
```

- [ ] **Step 7: Map the column in the three JDBC repositories**

In each of `JdbcDestinationRepository`, `JdbcChecklistRepository` and `JdbcItineraryRepository`:
- Add `"traveller_ids"` to the column list passed to `super(...)`.
- In `parametersOf`, add `values.put("traveller_ids", JdbcValues.nullableTextArray(x.getTravellerIds()));`, using that method's own parameter name for `x`.
- In `mapRow`, add `x.setTravellerIds(JdbcValues.nullableTextList(rs, "traveller_ids"));`.

- [ ] **Step 8: Run the full suite**

Run the full suite and the skipped check (Global Constraints).
Expected: PASS, `skipped="0"`. This also covers `Jdbc*RepositoryTest`, `MigrationsAreSchemaOnlyTest` (V2 has no DML), `YamlImporterTest` and `ImportVerifier`, which compare every field through Jackson, so the new field is imported and verified with no further change.

If a JDBC test fails with `column "traveller_ids" is of type text[] but expression is of type character varying`, the null bind is untyped. `nullableTextArray` must return the `SqlParameterValue` for null, not a bare `null`.

- [ ] **Step 9: Checkpoint.** Leave the changes uncommitted.

---

### Task 2: The resolver, `Travellers`

**Files:**
- Create: `planner-api/src/main/java/com/josephinealinea/planner/trips/api/Travellers.java`
- Test: `planner-api/src/test/java/com/josephinealinea/planner/trips/api/TravellersTest.java`

**Interfaces:**
- Consumes: `getTravellerIds()` from Task 1; `TripMembers.of(Trip).userIds()` and `TripMembers.of(Trip).validate(List<String>)` (existing).
- Produces:
  - `static Travellers of(Trip, List<Destination>, List<ChecklistItem>, List<ItineraryItem>)`
  - `List<String> ofDestination(Destination)`, `ofChecklistItem(ChecklistItem)`, `ofItineraryItem(ItineraryItem)`. These return the resolved list; empty = whole trip.
  - `static boolean includes(List<String> resolved, String userId)`
  - `Mine mineFor(String userId)`, where `record Mine(List<String> destinationIds, List<String> checklistItemIds, List<String> itineraryItemIds)`
  - `Named named()`, where `record Named(Map<String, List<String>> destinations, Map<String, List<String>> checklist, Map<String, List<String>> itinerary)`. Only records whose resolved list is not the whole trip appear.
  - `static List<String> change(Trip, List<String> stored, List<String> wanted, Boolean inherit)`
  - `static boolean changes(List<String> wanted, Boolean inherit)`

- [ ] **Step 1: Write the failing test**

Create `TravellersTest.java`:

```java
package com.josephinealinea.planner.trips.api;

import com.josephinealinea.planner.checklist.domain.ChecklistItem;
import com.josephinealinea.planner.destinations.domain.Destination;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.shared.ApiException;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.trips.domain.TripMember;
import com.josephinealinea.planner.trips.domain.TripRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Who is on what, worked out in one place. See the spec, section 2. */
class TravellersTest {

    private static final String ALEX = "user-alex";
    private static final String SAM = "user-sam";
    private static final String KIM = "user-kim";

    private static Trip trip(String... memberIds) {
        Trip trip = new Trip();
        trip.setId("trip-1");
        for (String id : memberIds) {
            trip.getMembers().add(new TripMember(id, id + "@example.com", TripRole.MEMBER, null));
        }
        return trip;
    }

    private static Destination destination(String id, List<String> travellers) {
        Destination d = new Destination();
        d.setId(id);
        d.setTravellerIds(travellers);
        return d;
    }

    private static ChecklistItem item(String id, String seededFrom, List<String> travellers) {
        ChecklistItem c = new ChecklistItem();
        c.setId(id);
        c.setSeededFromDestinationId(seededFrom);
        c.setTravellerIds(travellers);
        return c;
    }

    private static ItineraryItem entry(String id, String checklistItemId, String planId, List<String> travellers) {
        ItineraryItem i = new ItineraryItem();
        i.setId(id);
        i.setChecklistItemId(checklistItemId);
        i.setPlanId(planId);
        i.setTravellerIds(travellers);
        return i;
    }

    private final Destination cusco = destination("cusco", List.of(ALEX, SAM));
    private final Destination uyuni = destination("uyuni", null);
    private final ChecklistItem cuscoHotel = item("cusco-hotel", "cusco", null);
    private final ChecklistItem kimsTour = item("kims-tour", "cusco", List.of(KIM));
    private final ChecklistItem visas = item("visas", null, null);
    private final ItineraryItem hotelPlan = entry("hotel-plan", "cusco-hotel", null, null);
    private final ItineraryItem hotelNight = entry("hotel-night", "cusco-hotel", "hotel-plan", List.of(KIM));

    private Travellers travellers() {
        return Travellers.of(trip(ALEX, SAM, KIM),
                List.of(cusco, uyuni), List.of(cuscoHotel, kimsTour, visas), List.of(hotelPlan, hotelNight));
    }

    @Test
    void aDestinationWithNoListIsTheWholeTrip() {
        assertThat(travellers().ofDestination(uyuni)).isEmpty();
        assertThat(travellers().ofDestination(cusco)).containsExactly(ALEX, SAM);
    }

    @Test
    void aChecklistItemFollowsTheDestinationItWasSeededFromUntilSet() {
        assertThat(travellers().ofChecklistItem(cuscoHotel)).containsExactly(ALEX, SAM);
        assertThat(travellers().ofChecklistItem(kimsTour)).containsExactly(KIM);
        assertThat(travellers().ofChecklistItem(visas)).isEmpty();
    }

    @Test
    void anExplicitlyEmptyListIsTheWholeTripEvenUnderANamedParent() {
        ChecklistItem everyone = item("everyone", "cusco", List.of());
        Travellers t = Travellers.of(trip(ALEX, SAM, KIM), List.of(cusco), List.of(everyone), List.of());
        assertThat(t.ofChecklistItem(everyone)).isEmpty();
    }

    @Test
    void aPlanFollowsItsChecklistItemAndALaterDayAlwaysFollowsItsPlan() {
        assertThat(travellers().ofItineraryItem(hotelPlan)).containsExactly(ALEX, SAM);
        // The night carries a list of its own, and it is ignored.
        assertThat(travellers().ofItineraryItem(hotelNight)).containsExactly(ALEX, SAM);
    }

    @Test
    void aDeletedDestinationFallsThroughToTheWholeTrip() {
        Travellers t = Travellers.of(trip(ALEX, SAM, KIM), List.of(), List.of(cuscoHotel), List.of(hotelPlan));
        assertThat(t.ofChecklistItem(cuscoHotel)).isEmpty();
        assertThat(t.ofItineraryItem(hotelPlan)).isEmpty();
    }

    @Test
    void aBuddyWhoLeftDropsOutAndNobodyLeftMeansTheWholeTrip() {
        Travellers withoutSam = Travellers.of(trip(ALEX, KIM), List.of(cusco), List.of(kimsTour), List.of());
        assertThat(withoutSam.ofDestination(cusco)).containsExactly(ALEX);

        Travellers withoutKim = Travellers.of(trip(ALEX, SAM), List.of(cusco), List.of(kimsTour), List.of());
        assertThat(withoutKim.ofChecklistItem(kimsTour)).isEmpty();
    }

    @Test
    void mineListsWhatIncludesTheBuddyOrIsTheWholeTrip() {
        Travellers.Mine kims = travellers().mineFor(KIM);
        assertThat(kims.destinationIds()).containsExactly("uyuni");
        assertThat(kims.checklistItemIds()).containsExactly("kims-tour", "visas");
        assertThat(kims.itineraryItemIds()).isEmpty();

        Travellers.Mine sams = travellers().mineFor(SAM);
        assertThat(sams.destinationIds()).containsExactly("cusco", "uyuni");
        assertThat(sams.itineraryItemIds()).containsExactly("hotel-plan", "hotel-night");
    }

    @Test
    void namedListsOnlyWhatIsNotTheWholeTrip() {
        Travellers.Named named = travellers().named();
        assertThat(named.destinations()).containsOnlyKeys("cusco");
        assertThat(named.checklist()).containsOnlyKeys("cusco-hotel", "kims-tour");
        assertThat(named.itinerary()).containsOnlyKeys("hotel-plan", "hotel-night");
    }

    @Test
    void aChangeCanFollowTheParentSetTheWholeTripNameBuddiesOrLeaveItAlone() {
        Trip trip = trip(ALEX, SAM);
        assertThat(Travellers.change(trip, List.of(ALEX), null, true)).isNull();
        assertThat(Travellers.change(trip, List.of(ALEX), List.of(), null)).isEmpty();
        assertThat(Travellers.change(trip, null, List.of(SAM, ALEX), null)).containsExactly(SAM, ALEX);
        assertThat(Travellers.change(trip, List.of(ALEX), null, null)).containsExactly(ALEX);
        assertThat(Travellers.change(trip, null, null, null)).isNull();
        assertThatThrownBy(() -> Travellers.change(trip, null, List.of(KIM), null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("travel buddies");
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `cd planner-api && ./gradlew test --tests 'TravellersTest'`
Expected: FAIL to compile, `cannot find symbol: class Travellers`.

- [ ] **Step 3: Write `Travellers`**

Create `Travellers.java`:

```java
package com.josephinealinea.planner.trips.api;

import com.josephinealinea.planner.checklist.domain.ChecklistItem;
import com.josephinealinea.planner.destinations.domain.Destination;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.trips.domain.Trip;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Who is going to which part of a trip, worked out in one place.
 *
 * Destinations, checklist items and plans each carry an optional
 * travellerIds. Null means "not set: same as the parent", so choosing who goes
 * to Cusco once covers its seeded checklist and the plans made from it, and a
 * later change to Cusco carries down to everything nobody has edited. An empty
 * list is explicitly the whole trip. The chain is destination, then checklist
 * item (via seededFromDestinationId), then plan (via checklistItemId); a
 * stay's later days always follow their plan's own row, so a booking cannot
 * be half yours.
 *
 * <b>An empty result means the whole trip</b>, for the reason TripMembers
 * gives: buddies who have left drop out, and a record whose named travellers
 * have all gone must not become visible to nobody.
 *
 * Static and built from lists the caller already holds, like TripMembers: no
 * repository calls of its own. The page never re-derives any of this; it is
 * sent `mine` and `named` instead, so the planner and a personal published
 * page cannot disagree.
 */
public final class Travellers {

    /** What one buddy's "Mine" view shows, in the order given. */
    public record Mine(List<String> destinationIds,
                       List<String> checklistItemIds,
                       List<String> itineraryItemIds) {}

    /** Resolved lists for display, only where they are not the whole trip. */
    public record Named(Map<String, List<String>> destinations,
                        Map<String, List<String>> checklist,
                        Map<String, List<String>> itinerary) {}

    private final Set<String> members;
    private final Map<String, Destination> destinations = new LinkedHashMap<>();
    private final Map<String, ChecklistItem> checklist = new LinkedHashMap<>();
    private final Map<String, ItineraryItem> itinerary = new LinkedHashMap<>();

    private Travellers(Set<String> members) {
        this.members = members;
    }

    public static Travellers of(Trip trip,
                                List<Destination> destinations,
                                List<ChecklistItem> checklist,
                                List<ItineraryItem> itinerary) {
        Travellers t = new Travellers(new LinkedHashSet<>(TripMembers.of(trip).userIds()));
        destinations.forEach(d -> t.destinations.put(d.getId(), d));
        checklist.forEach(c -> t.checklist.put(c.getId(), c));
        itinerary.forEach(i -> t.itinerary.put(i.getId(), i));
        return t;
    }

    public List<String> ofDestination(Destination destination) {
        return normalise(destination.getTravellerIds());
    }

    public List<String> ofChecklistItem(ChecklistItem item) {
        if (item.getTravellerIds() != null) return normalise(item.getTravellerIds());
        String from = item.getSeededFromDestinationId();
        Destination destination = from == null ? null : destinations.get(from);
        // A deleted destination leaves its items following nothing: the whole
        // trip. Losing a place never hides the planning done against it.
        return destination == null ? List.of() : ofDestination(destination);
    }

    public List<String> ofItineraryItem(ItineraryItem entry) {
        if (entry.getPlanId() != null) {
            ItineraryItem plan = itinerary.get(entry.getPlanId());
            // A plan's own row has no planId; anything else is a hand-edited
            // file, which falls back to the whole trip rather than looping.
            return plan == null || plan.getPlanId() != null ? List.of() : ofItineraryItem(plan);
        }
        if (entry.getTravellerIds() != null) return normalise(entry.getTravellerIds());
        String itemId = entry.getChecklistItemId();
        ChecklistItem item = itemId == null ? null : checklist.get(itemId);
        return item == null ? List.of() : ofChecklistItem(item);
    }

    /** True when a resolved list is the whole trip or names this buddy. */
    public static boolean includes(List<String> resolved, String userId) {
        return resolved.isEmpty() || resolved.contains(userId);
    }

    public Mine mineFor(String userId) {
        return new Mine(
                idsWhere(destinations, d -> includes(ofDestination(d), userId)),
                idsWhere(checklist, c -> includes(ofChecklistItem(c), userId)),
                idsWhere(itinerary, i -> includes(ofItineraryItem(i), userId)));
    }

    public Named named() {
        return new Named(
                namedOnly(destinations, this::ofDestination),
                namedOnly(checklist, this::ofChecklistItem),
                namedOnly(itinerary, this::ofItineraryItem));
    }

    /**
     * What to store after a request. inherit=true clears the list back to
     * "follow the parent" and wins over anything else sent; a null list leaves
     * what is stored alone; any other list, an empty one included, is
     * validated against the trip's buddies and stored as sent.
     */
    public static List<String> change(Trip trip, List<String> stored, List<String> wanted, Boolean inherit) {
        if (Boolean.TRUE.equals(inherit)) return null;
        if (wanted == null) return stored;
        return TripMembers.of(trip).validate(wanted);
    }

    /** True when a request says anything at all about travellers. */
    public static boolean changes(List<String> wanted, Boolean inherit) {
        return wanted != null || Boolean.TRUE.equals(inherit);
    }

    private List<String> normalise(List<String> ids) {
        if (ids == null) return List.of();
        List<String> kept = new ArrayList<>();
        for (String id : ids) {
            if (id != null && members.contains(id) && !kept.contains(id)) kept.add(id);
        }
        return List.copyOf(kept);
    }

    private static <T> List<String> idsWhere(Map<String, T> byId, java.util.function.Predicate<T> test) {
        List<String> ids = new ArrayList<>();
        byId.forEach((id, value) -> { if (test.test(value)) ids.add(id); });
        return ids;
    }

    private static <T> Map<String, List<String>> namedOnly(Map<String, T> byId, Function<T, List<String>> resolve) {
        Map<String, List<String>> named = new LinkedHashMap<>();
        byId.forEach((id, value) -> {
            List<String> resolved = resolve.apply(value);
            if (!resolved.isEmpty()) named.put(id, resolved);
        });
        return named;
    }
}
```

- [ ] **Step 4: Run the test to see it pass**

Run: `cd planner-api && ./gradlew test --tests 'TravellersTest'`
Expected: PASS, 9 tests.

- [ ] **Step 5: Checkpoint.** Leave the changes uncommitted.

---

### Task 3: Writing travellers through the services and the API

**Files:**
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/destinations/api/DestinationService.java` (`Input`, `create`, `update`)
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/checklist/api/ChecklistService.java` (`Input`, `create`, `update`, new `all`)
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/itinerary/api/ItineraryService.java` (`Input`, `create`, `update`)
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/destinations/web/DestinationController.java`
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/checklist/web/ChecklistController.java`
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/itinerary/web/ItineraryController.java`
- Test: `planner-api/src/test/java/com/josephinealinea/planner/trips/TravellersWriteTest.java`

**Interfaces:**
- Consumes: `Travellers.change`, `Travellers.changes` (Task 2).
- Produces:
  - Each `Input` record gains two trailing components, `List<String> travellerIds` and `Boolean inheritTravellers`. Each also keeps a **second constructor with the old parameter list** that passes `null, null`, so the existing call sites (about 40 in tests) stay unchanged.
  - `public List<ChecklistItem> ChecklistService.all(Trip trip)`.

- [ ] **Step 1: Write the failing test**

Create `TravellersWriteTest.java`:

```java
package com.josephinealinea.planner.trips;

import com.josephinealinea.planner.budget.infra.YamlBudgetRepository;
import com.josephinealinea.planner.checklist.api.ChecklistService;
import com.josephinealinea.planner.checklist.domain.ChecklistCategory;
import com.josephinealinea.planner.checklist.domain.ChecklistItem;
import com.josephinealinea.planner.checklist.infra.YamlChecklistRepository;
import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.destinations.api.ChecklistSeeder;
import com.josephinealinea.planner.destinations.api.DestinationService;
import com.josephinealinea.planner.destinations.api.TripCountries;
import com.josephinealinea.planner.destinations.domain.Destination;
import com.josephinealinea.planner.destinations.infra.YamlDestinationRepository;
import com.josephinealinea.planner.geocoding.CountryCatalog;
import com.josephinealinea.planner.itinerary.api.BudgetSync;
import com.josephinealinea.planner.itinerary.api.ItineraryService;
import com.josephinealinea.planner.itinerary.api.PlanTemplates;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.itinerary.infra.YamlItineraryRepository;
import com.josephinealinea.planner.shared.ApiException;
import com.josephinealinea.planner.storage.TripLocks;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import com.josephinealinea.planner.trips.api.TripAccessService;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.trips.domain.TripMember;
import com.josephinealinea.planner.trips.domain.TripRole;
import com.josephinealinea.planner.trips.infra.YamlTripRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Setting, clearing and following travellers through the real services. See the spec, section 4. */
class TravellersWriteTest {

    private static final String SLUG = "latam-trip-2026";
    private static final String TRIP_ID = "trip-1";
    private static final String ALEX = "user-alex";
    private static final String SAM = "user-sam";

    private DestinationService destinationService;
    private ChecklistService checklistService;
    private ItineraryService itineraryService;
    private YamlDestinationRepository destinations;
    private YamlChecklistRepository checklist;
    private YamlItineraryRepository itinerary;
    private YamlBudgetRepository budget;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        AppProperties props = new AppProperties(
                new AppProperties.Storage(dir.toString()),
                new AppProperties.Publish(dir.resolve("published").toString(), "http://localhost:8080/p"),
                new AppProperties.Mail(null, null),
                new AppProperties.Security(null, null, false),
                new AppProperties.Cors(null),
                new AppProperties.Geocoding(null, null, 0, 0),
                new AppProperties.Weather(null, null, null, null, 0, 0, null),
                new AppProperties.Rates(null, null, "0 0 0 * * *", null),
                new AppProperties.Bootstrap(null, null),
                new AppProperties.Currencies(null, null, null));
        var store = new YamlStore();
        var paths = new YamlPaths(props);
        var locks = new TripLocks();
        destinations = new YamlDestinationRepository(store, paths, locks);
        checklist = new YamlChecklistRepository(store, paths, locks);
        itinerary = new YamlItineraryRepository(store, paths, locks);
        budget = new YamlBudgetRepository(store, paths, locks);
        var trips = new YamlTripRepository(store, paths, locks);

        Trip trip = new Trip();
        trip.setId(TRIP_ID);
        trip.setSlug(SLUG);
        trip.setTitle("LATAM Trip 2026");
        trip.setOwnerUserId(ALEX);
        trip.setStartDate(LocalDate.parse("2026-10-24"));
        trip.setEndDate(LocalDate.parse("2026-11-08"));
        trip.setDisplayCurrency("EUR");
        trip.getMembers().add(new TripMember(ALEX, "alex@example.com", TripRole.OWNER, null));
        trip.getMembers().add(new TripMember(SAM, "sam@example.com", TripRole.MEMBER, ALEX));
        trips.save(trip);

        var access = new TripAccessService(trips);
        var tripCountries = new TripCountries(destinations);
        var catalog = new CountryCatalog(RestClient.create());
        destinationService = new DestinationService(destinations, checklist, itinerary, budget,
                new ChecklistSeeder(), access, catalog, tripCountries);
        checklistService = new ChecklistService(checklist, itinerary, access, tripCountries);
        itineraryService = new ItineraryService(itinerary, destinations, checklistService, access,
                new PlanTemplates(), new BudgetSync(budget), catalog, tripCountries);
    }

    private Destination cusco(List<String> travellers) {
        return destinationService.create(TRIP_ID, ALEX, new DestinationService.Input(
                "Cusco", null, null, null, null, null, null, null, null, null, null,
                travellers, null)).destination();
    }

    @Test
    void aDestinationStoresWhoIsGoingAndSeedsItemsThatFollowIt() {
        Destination d = cusco(List.of(SAM));

        assertThat(destinations.findById(SLUG, d.getId()).orElseThrow().getTravellerIds()).containsExactly(SAM);
        assertThat(checklist.findAll(SLUG)).isNotEmpty()
                .allSatisfy(item -> assertThat(item.getTravellerIds()).isNull());
    }

    @Test
    void anEditCanNameBuddiesSetTheWholeTripOrGoBackToFollowing() {
        ChecklistItem item = checklistService.create(TRIP_ID, ALEX,
                new ChecklistService.Input(ChecklistCategory.OTHERS, "Visas", null, List.of(), null, null));
        assertThat(item.getTravellerIds()).isNull();

        checklistService.update(TRIP_ID, SAM, item.getId(),
                new ChecklistService.Input(null, null, null, null, List.of(SAM), null));
        ChecklistItem named = checklist.findById(SLUG, item.getId()).orElseThrow();
        assertThat(named.getTravellerIds()).containsExactly(SAM);
        assertThat(named.getUpdatedByUserId()).isEqualTo(SAM);

        checklistService.update(TRIP_ID, ALEX, item.getId(),
                new ChecklistService.Input(null, null, null, null, List.of(), null));
        assertThat(checklist.findById(SLUG, item.getId()).orElseThrow().getTravellerIds()).isNotNull().isEmpty();

        checklistService.update(TRIP_ID, ALEX, item.getId(),
                new ChecklistService.Input(null, null, null, null, List.of(SAM), true));
        assertThat(checklist.findById(SLUG, item.getId()).orElseThrow().getTravellerIds()).isNull();
    }

    @Test
    void anEditThatSaysNothingAboutTravellersLeavesThemAlone() {
        Destination d = cusco(List.of(SAM));
        destinationService.update(TRIP_ID, ALEX, d.getId(), new DestinationService.Input(
                "Cusco!", null, null, null, null, null, null, null, null, null, null));
        assertThat(destinations.findById(SLUG, d.getId()).orElseThrow().getTravellerIds()).containsExactly(SAM);
    }

    @Test
    void somebodyNotOnTheTripIsRefusedAndNothingIsSaved() {
        assertThatThrownBy(() -> cusco(List.of("user-stranger")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("travel buddies");
        assertThat(destinations.findAll(SLUG)).isEmpty();
    }

    @Test
    void aStaysLaterDaysStoreNothingAndRefuseTravellersOfTheirOwn() {
        ItineraryItem stay = itineraryService.create(TRIP_ID, ALEX, new ItineraryService.Input(
                null, ChecklistCategory.LODGING, "Hotel in Cusco",
                LocalDateTime.of(2026, 10, 25, 15, 0), LocalDateTime.of(2026, 10, 27, 11, 0),
                null, null, null, null, null, null, List.of(), List.of(SAM), null));
        List<ItineraryItem> days = itinerary.findAll(SLUG);
        ItineraryItem night = days.stream().filter(i -> stay.getId().equals(i.getPlanId())).findFirst().orElseThrow();

        assertThat(itinerary.findById(SLUG, stay.getId()).orElseThrow().getTravellerIds()).containsExactly(SAM);
        assertThat(night.getTravellerIds()).isNull();
        assertThatThrownBy(() -> itineraryService.update(TRIP_ID, ALEX, night.getId(), new ItineraryService.Input(
                null, null, null, null, null, null, null, null, null, null, null, null, List.of(ALEX), null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("follows its booking");
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `cd planner-api && ./gradlew compileTestJava`
Expected: FAIL, `constructor Input in record Input cannot be applied to given types` for the 13-, 6- and 14-argument calls.

- [ ] **Step 3: Extend the three `Input` records, keeping the old constructors**

`DestinationService.Input`: append the two components after `Boolean suppressChecklist`, and add the compatibility constructor inside the record:

```java
            Boolean suppressChecklist,
            /** Who's going. Null leaves it alone; [] is the whole trip. See Travellers. */
            List<String> travellerIds,
            /** True clears it back to "not set". */
            Boolean inheritTravellers) {

        /** The shape from before travellers existed: says nothing about them. */
        public Input(String name, String countryCode, String countryName, Double latitude,
                     Double longitude, Long geonameId, String timezone, LocalDate startDate,
                     LocalDate endDate, String notes, Boolean suppressChecklist) {
            this(name, countryCode, countryName, latitude, longitude, geonameId, timezone,
                    startDate, endDate, notes, suppressChecklist, null, null);
        }
    }
```

`ChecklistService.Input`:

```java
    public record Input(ChecklistCategory category,
                        String description,
                        String note,
                        List<String> countryCodes,
                        /** Who's going. Null leaves it alone; [] is the whole trip. See Travellers. */
                        List<String> travellerIds,
                        /** True clears it back to "not set", i.e. following its destination. */
                        Boolean inheritTravellers) {

        /** The shape from before travellers existed: says nothing about them. */
        public Input(ChecklistCategory category, String description, String note, List<String> countryCodes) {
            this(category, description, note, countryCodes, null, null);
        }
    }
```

`ItineraryService.Input`: append after `List<String> countryCodes`:

```java
                        List<String> countryCodes,
                        /** Who's going. Null leaves it alone; [] is the whole trip. See Travellers. */
                        List<String> travellerIds,
                        /** True clears it back to "not set", i.e. following its checklist item. */
                        Boolean inheritTravellers) {

        /** The shape from before travellers existed: says nothing about them. */
        public Input(String checklistItemId, ChecklistCategory category, String description,
                     LocalDateTime startAt, LocalDateTime endAt, Boolean allDay, BigDecimal cost,
                     String currency, Boolean costCharged, List<String> costSharedByUserIds,
                     String costPaidByUserId, List<String> countryCodes) {
            this(checklistItemId, category, description, startAt, endAt, allDay, cost, currency,
                    costCharged, costSharedByUserIds, costPaidByUserId, countryCodes, null, null);
        }
    }
```

- [ ] **Step 4: Store the change in each service, validated before anything is saved**

Add `import com.josephinealinea.planner.trips.api.Travellers;` to all three services.

`DestinationService.create`: after `apply(destination, input);` and before `Audit.created(...)`:

```java
        destination.setTravellerIds(Travellers.change(trip, null,
                input.travellerIds(), input.inheritTravellers()));
```

`DestinationService.update`: after `apply(destination, input);` and before `Audit.touched(...)`:

```java
        destination.setTravellerIds(Travellers.change(trip, destination.getTravellerIds(),
                input.travellerIds(), input.inheritTravellers()));
```

`ChecklistService.create`: after `item.setCountryCodes(...)`:

```java
        item.setTravellerIds(Travellers.change(trip, null, input.travellerIds(), input.inheritTravellers()));
```

`ChecklistService.update`: before `Audit.touched(item, userId);`:

```java
        item.setTravellerIds(Travellers.change(trip, item.getTravellerIds(),
                input.travellerIds(), input.inheritTravellers()));
```

`ChecklistService`, a new method after `list`:

```java
    /** Every item on the trip, unordered — for callers that resolve travellers. */
    public List<ChecklistItem> all(Trip trip) {
        return checklist.findAll(trip.getSlug());
    }
```

`ItineraryService.create`: after `plan.setCountryCodes(...)`:

```java
        plan.setTravellerIds(Travellers.change(trip, null, input.travellerIds(), input.inheritTravellers()));
```

`ItineraryService.update`: at the top, directly after `ItineraryItem plan = require(trip, itemId);`:

```java
        // A later day of a stay always follows its booking (see Travellers), so
        // a list of its own would be stored and then ignored. Refused instead.
        if (plan.getPlanId() != null && Travellers.changes(input.travellerIds(), input.inheritTravellers())) {
            throw ApiException.badRequest(
                    "This day follows its booking. Change who's going on the booking itself.");
        }
        plan.setTravellerIds(Travellers.change(trip, plan.getTravellerIds(),
                input.travellerIds(), input.inheritTravellers()));
```

`spreadPlanOverItsDays` needs no change: it never calls `setTravellerIds`, so later days store nothing.

- [ ] **Step 5: Accept the fields in the controllers**

In `ChecklistController.CreateRequest` and `PatchRequest`, add after `List<String> countryCodes`:

```java
            List<String> countryCodes,
            List<String> travellerIds,
            Boolean inheritTravellers) {

        ChecklistService.Input toInput() {
            return new ChecklistService.Input(category, description, note, countryCodes,
                    travellerIds, inheritTravellers);
        }
```

In `DestinationController.DestinationRequest` and `PatchDestinationRequest`, add `List<String> travellerIds, Boolean inheritTravellers` after `Boolean suppressChecklist`, and pass them as the last two arguments of `new DestinationService.Input(...)`. Add `import java.util.List;` if missing.

In `ItineraryController.CreateRequest` and `PatchRequest`, add `List<String> travellerIds, Boolean inheritTravellers` after `List<String> countryCodes`, and pass them as the last two arguments of `new ItineraryService.Input(...)`.

- [ ] **Step 6: Run the new test, then the full suite**

Run: `cd planner-api && ./gradlew test --tests 'TravellersWriteTest'`, then the full suite and the skipped check.
Expected: PASS everywhere, `skipped="0"`.

- [ ] **Step 7: Checkpoint.** Leave the changes uncommitted.

---

### Task 4: A plan's first cost is shared by its travellers

**Files:**
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/itinerary/api/ItineraryService.java` (`create`, `update`, new `travellersOf`)
- Test: `planner-api/src/test/java/com/josephinealinea/planner/trips/TravellersWriteTest.java` (add tests)

**Interfaces:**
- Consumes: `Travellers.of(...).ofItineraryItem(...)`, `ChecklistService.all(Trip)`.
- Produces: no new public API. The behaviour: when a cost creates a budget row and the request sent no `costSharedByUserIds`, the row's `sharedByUserIds` is the plan's resolved travellers.

- [ ] **Step 1: Add the failing tests to `TravellersWriteTest`**

```java
    private ItineraryItem planFromCuscoChecklist(BigDecimal cost, List<String> costSharers) {
        cusco(List.of(ALEX, SAM));
        ChecklistItem seeded = checklist.findAll(SLUG).get(0);
        return itineraryService.create(TRIP_ID, ALEX, new ItineraryService.Input(
                seeded.getId(), null, "Bus to Cusco", LocalDateTime.of(2026, 10, 25, 9, 0), null,
                null, cost, "EUR", null, costSharers, null, List.of()));
    }

    @Test
    void aFirstCostWithNoSharersSentIsSharedByThePlansTravellers() {
        ItineraryItem plan = planFromCuscoChecklist(new BigDecimal("60.00"), null);

        assertThat(budget.findById(SLUG, plan.getBudgetItemId()).orElseThrow().getSharedByUserIds())
                .containsExactly(ALEX, SAM);
    }

    @Test
    void sharersSentExplicitlyAreUsedAsSentEvenTheWholeTrip() {
        ItineraryItem plan = planFromCuscoChecklist(new BigDecimal("60.00"), List.of());

        assertThat(budget.findById(SLUG, plan.getBudgetItemId()).orElseThrow().getSharedByUserIds()).isEmpty();
    }

    @Test
    void laterChangesToWhoIsGoingNeverMoveTheMoney() {
        ItineraryItem plan = planFromCuscoChecklist(new BigDecimal("60.00"), null);
        Destination cusco = destinations.findAll(SLUG).get(0);

        destinationService.update(TRIP_ID, ALEX, cusco.getId(), new DestinationService.Input(
                null, null, null, null, null, null, null, null, null, null, null, List.of(SAM), null));
        itineraryService.update(TRIP_ID, ALEX, plan.getId(), new ItineraryService.Input(
                null, null, null, null, null, null, null, null, null, null, null, null, List.of(ALEX), null));

        assertThat(budget.findById(SLUG, plan.getBudgetItemId()).orElseThrow().getSharedByUserIds())
                .containsExactly(ALEX, SAM);
    }

    @Test
    void aCostAddedLaterToAnExistingPlanAlsoStartsWithItsTravellers() {
        ItineraryItem plan = planFromCuscoChecklist(null, null);
        itineraryService.update(TRIP_ID, ALEX, plan.getId(), new ItineraryService.Input(
                null, null, null, null, null, null, new BigDecimal("40.00"), "EUR", null, null, null, null));

        String rowId = itinerary.findById(SLUG, plan.getId()).orElseThrow().getBudgetItemId();
        assertThat(budget.findById(SLUG, rowId).orElseThrow().getSharedByUserIds()).containsExactly(ALEX, SAM);
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `cd planner-api && ./gradlew test --tests 'TravellersWriteTest'`
Expected: `aFirstCostWithNoSharersSentIsSharedByThePlansTravellers` and `aCostAddedLaterToAnExistingPlanAlsoStartsWithItsTravellers` FAIL, with sharers `[]` instead of `[user-alex, user-sam]`. The other two pass already.

- [ ] **Step 3: Default the sharers of a new row**

In `ItineraryService.create`, replace `List<String> sharers = validatedSharers(trip, input.costSharedByUserIds());` with:

```java
        List<String> sharers = validatedSharers(trip, input.costSharedByUserIds());
        // A cost starts shared by whoever the plan is for. Copied once: the row
        // is ordinary money from then on, and nothing about who's going ever
        // rewrites it. See Travellers.
        if (sharers == null && plan.hasCost()) sharers = travellersOf(trip, plan);
```

In `ItineraryService.update`, replace the same line with:

```java
        List<String> sharers = validatedSharers(trip, input.costSharedByUserIds());
        // Only for a row about to be created: an existing row's sharers are
        // never re-derived from the plan.
        if (sharers == null && plan.hasCost() && plan.getBudgetItemId() == null) {
            sharers = travellersOf(trip, plan);
        }
```

Add the helper beside `validatedSharers`:

```java
    /** The plan's resolved travellers, [] for the whole trip. */
    private List<String> travellersOf(Trip trip, ItineraryItem plan) {
        return new ArrayList<>(Travellers.of(trip, destinations.findAll(trip.getSlug()),
                checklistService.all(trip), itinerary.findAll(trip.getSlug())).ofItineraryItem(plan));
    }
```

- [ ] **Step 4: Run the tests, then the full suite**

Run: `cd planner-api && ./gradlew test --tests 'TravellersWriteTest'`, then the full suite and the skipped check.
Expected: PASS, `skipped="0"`. `BudgetSyncTest`, `StayNightsTest` and `BudgetSharingTest` must be unchanged. Their plans have no travellers, so the default resolves to `[]`, the same as before.

- [ ] **Step 5: Checkpoint.** Leave the changes uncommitted.

---

### Task 5: Send `mine` and `travellers`, and count My Trips per buddy

**Files:**
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/trips/api/TripViews.java` (`TripDetail`)
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/trips/api/TripViewAssembler.java` (`summary`, `detail`)
- Test: `planner-api/src/test/java/com/josephinealinea/planner/trips/TravellersViewTest.java`

**Interfaces:**
- Consumes: `Travellers.of`, `mineFor`, `named`, `includes` (Task 2).
- Produces: `TripDetail` gains two trailing components, `Travellers.Mine mine` and `Travellers.Named travellers`. JSON keys: `mine.destinationIds`, `mine.checklistItemIds`, `mine.itineraryItemIds`, `travellers.destinations|checklist|itinerary` (id → user ids). `TripSummary.checklistTotal/checklistCompleted` count the viewer's items.

- [ ] **Step 1: Write the failing test**

Create `TravellersViewTest.java`. It wires the same repositories as `PersonalPageTest.setUp`, which is where the `TripViewAssembler` constructor arguments come from:

```java
package com.josephinealinea.planner.trips;

import com.josephinealinea.planner.budget.api.BudgetService;
import com.josephinealinea.planner.budget.infra.YamlBudgetRepository;
import com.josephinealinea.planner.checklist.domain.ChecklistItem;
import com.josephinealinea.planner.checklist.domain.ChecklistStatus;
import com.josephinealinea.planner.checklist.infra.YamlChecklistRepository;
import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.destinations.api.TripCountries;
import com.josephinealinea.planner.destinations.domain.Destination;
import com.josephinealinea.planner.destinations.infra.YamlDestinationRepository;
import com.josephinealinea.planner.identity.domain.User;
import com.josephinealinea.planner.identity.infra.YamlUserRepository;
import com.josephinealinea.planner.itinerary.infra.YamlItineraryRepository;
import com.josephinealinea.planner.publish.infra.FileSystemPageStore;
import com.josephinealinea.planner.rates.TestRates;
import com.josephinealinea.planner.storage.TripLocks;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import com.josephinealinea.planner.trips.api.TripAccessService;
import com.josephinealinea.planner.trips.api.TripViewAssembler;
import com.josephinealinea.planner.trips.api.TripViews;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.trips.domain.TripMember;
import com.josephinealinea.planner.trips.domain.TripRole;
import com.josephinealinea.planner.trips.infra.YamlTripRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The server decides what is "mine"; the page only filters on it. See the spec, section 3. */
class TravellersViewTest {

    private static final String SLUG = "latam-trip-2026";
    private static final String ALEX = "user-alex";
    private static final String SAM = "user-sam";

    private TripViewAssembler views;
    private Trip trip;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        AppProperties props = new AppProperties(
                new AppProperties.Storage(dir.toString()),
                new AppProperties.Publish(dir.resolve("published").toString(), "http://localhost:8080/p"),
                new AppProperties.Mail(null, null),
                new AppProperties.Security(null, null, false),
                new AppProperties.Cors(null),
                new AppProperties.Geocoding(null, null, 0, 0),
                new AppProperties.Weather(null, null, null, null, 0, 0, null),
                new AppProperties.Rates(null, null, "0 0 0 * * *", null),
                new AppProperties.Bootstrap(null, null),
                new AppProperties.Currencies(null, null, null));
        var store = new YamlStore();
        var paths = new YamlPaths(props);
        var locks = new TripLocks();
        var destinations = new YamlDestinationRepository(store, paths, locks);
        var checklist = new YamlChecklistRepository(store, paths, locks);
        var itinerary = new YamlItineraryRepository(store, paths, locks);
        var budget = new YamlBudgetRepository(store, paths, locks);
        var trips = new YamlTripRepository(store, paths, locks);
        var users = new YamlUserRepository(store, paths, locks);
        for (String id : List.of(ALEX, SAM)) {
            User u = new User();
            u.setId(id);
            u.setEmail(id + "@example.com");
            users.save(u);
        }

        trip = new Trip();
        trip.setId("trip-1");
        trip.setSlug(SLUG);
        trip.setTitle("LATAM Trip 2026");
        trip.setOwnerUserId(ALEX);
        trip.setDisplayCurrency("EUR");
        trip.getMembers().add(new TripMember(ALEX, "alex@example.com", TripRole.OWNER, null));
        trip.getMembers().add(new TripMember(SAM, "sam@example.com", TripRole.MEMBER, ALEX));
        trips.save(trip);

        Destination uyuni = new Destination();
        uyuni.setId("uyuni");
        uyuni.setName("Uyuni");
        uyuni.setTravellerIds(List.of(SAM));
        destinations.save(SLUG, uyuni);

        ChecklistItem tour = new ChecklistItem();
        tour.setId("uyuni-tour");
        tour.setDescription("Book the salt flats tour");
        tour.setSeededFromDestinationId("uyuni");
        tour.setStatus(ChecklistStatus.COMPLETED);
        ChecklistItem visas = new ChecklistItem();
        visas.setId("visas");
        visas.setDescription("Visas");
        visas.setStatus(ChecklistStatus.TODO);
        checklist.save(SLUG, tour);
        checklist.save(SLUG, visas);

        var access = new TripAccessService(trips);
        var budgets = new BudgetService(budget, itinerary, destinations, users, access,
                new TripCountries(destinations), TestRates.empty(store, paths, props));
        views = new TripViewAssembler(users, destinations, checklist, itinerary, budgets,
                TestRates.empty(store, paths, props), new FileSystemPageStore(store, paths), props);
    }

    @Test
    void eachBuddyIsToldWhichRecordsAreTheirs() {
        TripViews.TripDetail alex = views.detail(trip, ALEX);
        TripViews.TripDetail sam = views.detail(trip, SAM);

        assertThat(alex.mine().destinationIds()).isEmpty();
        assertThat(alex.mine().checklistItemIds()).containsExactly("visas");
        assertThat(sam.mine().destinationIds()).containsExactly("uyuni");
        assertThat(sam.mine().checklistItemIds()).containsExactlyInAnyOrder("uyuni-tour", "visas");
        // Everything is still sent: focus view, not privacy.
        assertThat(alex.destinations()).extracting(Destination::getId).containsExactly("uyuni");
    }

    @Test
    void namesAreSentOnlyForWhatIsNotTheWholeTrip() {
        TripViews.TripDetail alex = views.detail(trip, ALEX);

        assertThat(alex.travellers().destinations()).containsOnlyKeys("uyuni");
        assertThat(alex.travellers().checklist()).containsOnlyKeys("uyuni-tour");
        assertThat(alex.travellers().checklist().get("uyuni-tour")).containsExactly(SAM);
    }

    @Test
    void myTripsCountsTheBuddysOwnChecklist() {
        assertThat(views.summary(trip, ALEX).checklistTotal()).isEqualTo(1);
        assertThat(views.summary(trip, ALEX).checklistCompleted()).isZero();
        assertThat(views.summary(trip, SAM).checklistTotal()).isEqualTo(2);
        assertThat(views.summary(trip, SAM).checklistCompleted()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `cd planner-api && ./gradlew test --tests 'TravellersViewTest'`
Expected: FAIL to compile, `cannot find symbol: method mine()`.

- [ ] **Step 3: Extend `TripDetail`**

In `TripViews.TripDetail`, after `PublishView publish`:

```java
                             PublishView publish,
                             /**
                              * Which destinations, checklist items and plans
                              * are the signed-in member's, for the Mine view.
                              * Decided here rather than in the page, the same
                              * way budget.shares is. See Travellers.
                              */
                             Travellers.Mine mine,
                             /** Who's going, by id, only where it is not the whole trip. */
                             Travellers.Named travellers) {}
```

- [ ] **Step 4: Fill it in the assembler, and count My Trips per buddy**

In `TripViewAssembler.detail`, read the three lists once and pass them on:

```java
        var tripDestinations = destinations.findAllOrdered(trip.getSlug());
        var tripChecklist = checklist.findAllOrdered(trip.getSlug());
        var tripItinerary = itinerary.findAllOrdered(trip.getSlug());
        Travellers travellers = Travellers.of(trip, tripDestinations, tripChecklist, tripItinerary);
```

In the `new TripViews.TripDetail(...)` call, replace the three `findAllOrdered` arguments with `tripDestinations, tripChecklist, tripItinerary`. After `publish(trip, currentUserId)`, add:

```java
                publish(trip, currentUserId),
                travellers.mineFor(currentUserId),
                travellers.named());
```

In `TripViewAssembler.summary`, replace the first two lines of the method with:

```java
        List<ChecklistItem> all = checklist.findAll(trip.getSlug());
        // The card agrees with the trip's default Mine view: this member's items.
        Travellers travellers = Travellers.of(trip, destinations.findAll(trip.getSlug()), all, List.of());
        List<ChecklistItem> items = currentUserId == null ? all : all.stream()
                .filter(item -> Travellers.includes(travellers.ofChecklistItem(item), currentUserId))
                .toList();
        long completed = items.stream().filter(ChecklistItem::isCompleted).count();
```

- [ ] **Step 5: Run the test, then the full suite**

Expected: PASS, `skipped="0"`. `DatabaseModeApplicationTest` assembles a real `TripDetail` on Postgres, and it must still pass.

- [ ] **Step 6: Checkpoint.** Leave the changes uncommitted.

---

### Task 6: A personal published page shows only that buddy's parts

**Files:**
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/publish/api/StaticSiteRenderer.java` (`snapshot`)
- Test: `planner-api/src/test/java/com/josephinealinea/planner/publish/PersonalPageTest.java` (add tests)

**Interfaces:**
- Consumes: `Travellers.of`, `ofDestination`, `ofChecklistItem`, `ofItineraryItem`, `includes`.
- Produces: no API change. Published payloads are unchanged in shape.

- [ ] **Step 1: Add the failing tests to `PersonalPageTest`**

Add after `anotherMembersSpendingIsNowhereInThePersonalPageFile`:

```java
    /**
     * Who's going decides what a personal page lists, and — as with the money
     * above — what it leaves out is left out of the file, not hidden in it.
     */
    @Test
    void aPersonalPageLeavesOutDestinationsTheBuddyIsNotGoingTo() throws Exception {
        Destination uyuni = new Destination();
        uyuni.setId("uyuni");
        uyuni.setTripId(TRIP_ID);
        uyuni.setName("Salar de Uyuni");
        uyuni.setCountryName("Bolivia");
        uyuni.setTravellerIds(List.of(SAM));
        destinationsRepo.save(SLUG, uyuni);
        wantsOwnPage(ALEX);
        wantsOwnPage(SAM);

        publish.publish(TRIP_ID, ALEX, "minima");

        assertThat(read(personal("alex"))).doesNotContain("Salar de Uyuni").doesNotContain("Bolivia");
        assertThat(read(personal("sam"))).contains("Salar de Uyuni");
        assertThat(read(publishDir.resolve(SLUG).resolve("index.html"))).contains("Salar de Uyuni");
    }

    @Test
    void noBuddysIdReachesAPublishedFile() throws Exception {
        Destination uyuni = new Destination();
        uyuni.setId("uyuni");
        uyuni.setTripId(TRIP_ID);
        uyuni.setName("Salar de Uyuni");
        uyuni.setTravellerIds(List.of(SAM));
        destinationsRepo.save(SLUG, uyuni);
        wantsOwnPage(SAM);

        publish.publish(TRIP_ID, ALEX, "minima");

        for (Path file : List.of(publishDir.resolve(SLUG).resolve("index.html"), personal("sam"))) {
            assertThat(read(file)).doesNotContain(SAM).doesNotContain(ALEX);
        }
    }
```

For these to compile, keep the destination repository `setUp` creates in a field. Add `private YamlDestinationRepository destinationsRepo;` beside the other fields, and in `setUp` change `var destinations = new YamlDestinationRepository(store, paths, locks);` to:

```java
        var destinations = destinationsRepo = new YamlDestinationRepository(store, paths, locks);
```

- [ ] **Step 2: Run them to see them fail**

Run: `cd planner-api && ./gradlew test --tests 'PersonalPageTest'`
Expected: `aPersonalPageLeavesOutDestinationsTheBuddyIsNotGoingTo` FAILS: Alex's page contains "Salar de Uyuni". `noBuddysIdReachesAPublishedFile` should PASS already. If it fails, stop and report it: it would mean a user id already reaches published files, a bug that predates this work.

- [ ] **Step 3: Filter the snapshot for a viewer**

In `StaticSiteRenderer.snapshot`, replace the three `var all… = ….findAllOrdered(slug);` lines with:

```java
        var tripDestinations = destinations.findAllOrdered(slug);
        var tripChecklist = checklist.findAllOrdered(slug);
        var tripItinerary = itinerary.findAllOrdered(slug);
        // A personal page lists only its viewer's parts, and leaves the rest
        // out of the file rather than hiding it: see Travellers, and the
        // "not displayed means not shipped" rule this page already keeps.
        Travellers travellers = Travellers.of(trip, tripDestinations, tripChecklist, tripItinerary);
        String viewerId = viewer == null ? null : viewer.getId();
        var allDestinations = viewerId == null ? tripDestinations : tripDestinations.stream()
                .filter(d -> Travellers.includes(travellers.ofDestination(d), viewerId)).toList();
        var allChecklist = viewerId == null ? tripChecklist : tripChecklist.stream()
                .filter(c -> Travellers.includes(travellers.ofChecklistItem(c), viewerId)).toList();
        var allItinerary = viewerId == null ? tripItinerary : tripItinerary.stream()
                .filter(i -> Travellers.includes(travellers.ofItineraryItem(i), viewerId)).toList();
```

Add `import com.josephinealinea.planner.trips.api.Travellers;`. The rest of `snapshot` already derives flags, route, country chips and days from `allDestinations`, `allChecklist` and `allItinerary`, so it follows automatically.

- [ ] **Step 4: Run the tests, then the full suite**

Expected: PASS, `skipped="0"`, including `StaticSiteRendererTest` and `PublishApprovalTest`, unchanged.

- [ ] **Step 5: Checkpoint.** Leave the changes uncommitted.

---

### Task 7: The Mine / Whole trip switch

**Files:**
- Create: `planner-web/js/trip-scope.js`
- Create: `planner-web/js/pages/trip/scope.js`
- Modify: `planner-web/js/pages/trip.js` (merge `scopeTab`, `reload`, `countFor`)
- Modify: `planner-web/js/pages/trip/overview.js`, `destinations.js`, `checklist.js`, `itinerary.js`
- Modify: `planner-web/trip.html` (switch, hint lines, destinations loop)
- Modify: `planner-web/scss/pages/_trip.scss`

**Interfaces:**
- Consumes: `detail.mine`, `detail.travellers` from Task 5.
- Produces, on the merged Alpine component:
  - `scope` (`'mine'|'trip'`), `setScope(scope)`, getter `showingMine`
  - getters `scopedDestinations`, `scopedChecklist`, `scopedItinerary`
  - `hiddenLabel(kind)`, where kind is `'destinations'|'checklist'|'itinerary'`, returning `''` when nothing is hidden
  - `travellerNames(kind, id)`, returning `''` for the whole trip
  - `namesOf(ids)`
  - `isHiddenByScope(kind, id)`

- [ ] **Step 1: Create `js/trip-scope.js`**

```js
/**
 * Mine or Whole trip: which part of a trip the planner shows. Remembered in
 * this browser, like the panel mode beside it, and never sent anywhere — it is
 * a way of looking, not a setting of the trip. Defaults to Mine.
 */
const KEY = 'tripScope';

export function savedScope() {
  try {
    return localStorage.getItem(KEY) === 'trip' ? 'trip' : 'mine';
  } catch {
    return 'mine';
  }
}

export function saveScope(scope) {
  try {
    localStorage.setItem(KEY, scope === 'trip' ? 'trip' : 'mine');
  } catch { /* private mode: the choice lasts for this page only */ }
}
```

- [ ] **Step 2: Create `js/pages/trip/scope.js`**

```js
import { savedScope, saveScope } from '../../trip-scope.js';

const KEYS = { destinations: 'destinationIds', checklist: 'checklistItemIds', itinerary: 'itineraryItemIds' };
const NOUNS = {
  destinations: ['destination', 'destinations'],
  checklist: ['checklist item', 'checklist items'],
  itinerary: ['plan', 'plans'],
};

/**
 * Mine / Whole trip. The API decides which records are this member's
 * (`mine`, see Travellers on the API) and this only filters on it — never
 * re-deriving who is going, or the planner and a personal published page
 * could disagree. Link pickers keep reading the whole lists; only what is
 * shown reads the scoped ones.
 */
export function scopeTab() {
  return {
    scope: savedScope(),
    mine: { destinationIds: [], checklistItemIds: [], itineraryItemIds: [] },
    namedTravellers: { destinations: {}, checklist: {}, itinerary: {} },

    setScope(scope) {
      this.scope = scope;
      saveScope(scope);
    },

    get showingMine() { return this.scope === 'mine'; },

    get scopedDestinations() { return this.inScope('destinations', this.destinations); },
    get scopedChecklist() { return this.inScope('checklist', this.checklist); },
    get scopedItinerary() { return this.inScope('itinerary', this.itinerary); },

    inScope(kind, all) {
      if (!this.showingMine) return all;
      const ids = this.mine[KEYS[kind]] || [];
      return all.filter((record) => ids.includes(record.id));
    },

    /** "2 more destinations", or '' when Mine hides nothing of this kind. */
    hiddenLabel(kind) {
      if (!this.showingMine) return '';
      const all = { destinations: this.destinations, checklist: this.checklist, itinerary: this.itinerary }[kind];
      const hidden = all.length - this.inScope(kind, all).length;
      if (hidden <= 0) return '';
      const [one, many] = NOUNS[kind];
      return `${hidden} more ${hidden === 1 ? one : many}`;
    },

    /** True when a just-saved record is not on this member's list. */
    isHiddenByScope(kind, id) {
      return !(this.mine[KEYS[kind]] || []).includes(id);
    },

    namesOf(ids) {
      return (ids || [])
        .map((id) => this.members.find((m) => m.userId === id)?.displayName)
        .filter(Boolean)
        .join(', ');
    },

    /** "Maia, Joey" for a record not for the whole trip, '' otherwise. */
    travellerNames(kind, id) {
      return this.namesOf((this.namedTravellers[kind] || {})[id]);
    },
  };
}
```

- [ ] **Step 3: Merge it and fill it on reload (`js/pages/trip.js`)**

- Add `import { scopeTab } from './trip/scope.js';` beside the other tab imports.
- Add `scopeTab(),` to the merge list, directly before `overviewTab(),`.
- In `reload()`, after `this.itinerary = detail.itinerary || [];`, add:

```js
        this.mine = detail.mine || { destinationIds: [], checklistItemIds: [], itineraryItemIds: [] };
        this.namedTravellers = detail.travellers || { destinations: {}, checklist: {}, itinerary: {} };
```

- In `countFor`, change the three cases to read the scoped lists:

```js
        case 'destinations': return this.scopedDestinations.length;
        case 'checklist': return this.scopedChecklist.length;
        case 'itinerary': return this.scopedItinerary.length;
```

- [ ] **Step 4: Point what is shown at the scoped lists**

`js/pages/trip/overview.js`: in `stats()`, `upNext()` and `needsPlanning()`, replace every `this.destinations`, `this.checklist` and `this.itinerary` with `this.scopedDestinations`, `this.scopedChecklist` and `this.scopedItinerary`.

`js/pages/trip/destinations.js`:
- `destSelected` returns `selectedPresent(this.destSelectedIds, this.scopedDestinations);`
- in `checklistCountFor`, replace `this.checklist.filter(` with `this.scopedChecklist.filter(`

`js/pages/trip/checklist.js`:
- `filteredChecklist` starts from `this.scopedChecklist.filter((item) => {`
- the progress function at the `const done = this.checklist.filter(` line reads `this.scopedChecklist` in both lines
- **Leave** `const found = this.checklist.find(...)` (drawer lookup by id) on the whole list.

`js/pages/trip/itinerary.js`:
- `filteredItinerary` starts from `this.scopedItinerary.filter((item) => {`
- `filteredWeather` becomes:

```js
    get filteredWeather() {
      if (this.itinShow === 'ITINERARY') return [];
      // The lookup covers every destination whatever the view (one call per
      // trip); Mine only chooses which rows show.
      if (!this.showingMine) return this.weatherDays;
      const mine = this.mine.destinationIds || [];
      return this.weatherDays.filter((day) => !day.destinationId || mine.includes(day.destinationId));
    },
```

Bulk selection needs no change beyond `destSelected`. `checkSelected` and `itinSelected` already intersect with the filtered lists (`selectedPresent`), so switching to Mine drops hidden rows from the selection and "Delete selected" can only act on visible rows.

- [ ] **Step 5: Add the switch, the hint lines and the scoped destinations loop (`trip.html`)**

In the `.trip-head-meta` block, after the buddy-count `<span>`, add:

```html
            <span class="scope-switch" role="group" aria-label="Show">
              <button type="button" class="chip" :aria-pressed="scope === 'mine'"
                      @click="setScope('mine')">Mine</button>
              <button type="button" class="chip" :aria-pressed="scope === 'trip'"
                      @click="setScope('trip')">Whole trip</button>
            </span>
```

Directly after the closing `</div>` of the `section-head` in each of `id="panel-destinations"`, `id="panel-checklist"` and `id="panel-itinerary"`, add one line, with `destinations`, `checklist` and `itinerary` respectively:

```html
        <p class="scope-hint" x-show="hiddenLabel('destinations')" x-cloak>
          Showing your part · <strong x-text="hiddenLabel('destinations')"></strong> on the whole trip ·
          <button type="button" class="btn btn-ghost btn-sm" @click="setScope('trip')">Show whole trip</button>
        </p>
```

Change `<template x-for="destination in destinations" :key="destination.id">` to `<template x-for="destination in scopedDestinations" :key="destination.id">`.

- [ ] **Step 6: Style them (`scss/pages/_trip.scss`)**

```scss
// Mine / Whole trip, in the trip header.
.scope-switch {
  display: inline-flex;
  gap: t.$space-1;
  margin-left: t.$space-2;
  vertical-align: middle;
}

// "Showing your part · 2 more destinations on the whole trip · Show whole trip"
.scope-hint {
  margin: 0 0 t.$space-3;
  color: var(--tp-muted);
  font-size: 0.88rem;
}
```

Bump every `.css?v=75` to `.css?v=76` in `planner-web/*.html`, then run `cd planner-web && npm run css`.

- [ ] **Step 7: Check it in the browser (demo API on scratch data, see Global Constraints)**

As the demo owner, add a buddy, add two destinations, and set one of them to only the buddy with a direct API call (`PATCH /api/v1/trips/{id}/destinations/{destId}` with `{"travellerIds":["<buddy id>"]}`). Then check that:
- **Mine** hides that destination, its seeded checklist items, and its weather rows.
- The hint reads "1 more destination on the whole trip", and "Show whole trip" switches the view.
- The tab badges follow the switch.
- The choice survives a reload.
- Ticking a row in Whole trip and switching to Mine drops it from "Delete selected".

- [ ] **Step 8: Checkpoint.** Leave the changes uncommitted.

---

### Task 8: The "Who's going" picker, chips and the cost default in the forms

**Files:**
- Create: `planner-web/js/traveller-picker.js`
- Modify: `planner-web/js/pages/trip/destinations.js` (`destForm`, save payload)
- Modify: `planner-web/js/pages/trip/checklist.js` (`newCheck`, `drawerForm`, `planForm`, payloads)
- Modify: `planner-web/js/pages/trip/itinerary.js` (`entryForm`, payload)
- Modify: `planner-web/trip.html` (five picker blocks, chips on rows)

**Interfaces:**
- Consumes: `travellerIds` on records, `namedTravellers`, `namesOf`, `travellerNames`, `isHiddenByScope`, `tripSharers` (existing getter in `budget.js`).
- Produces, from `traveller-picker.js`:
  - `travellersFromRecord(record, rootIsTrip)` returns a state `{ mode: 'inherit'|'everyone'|'named', ids: [] }`
  - `newTravellers(rootIsTrip)`
  - `chooseTravellers(state, inheritedIds)`, `followParent(state)`, `toggleTraveller(state, userId)`, `everyoneGoes(state)`
  - `travellersPayload(state, initial)` returns `{}` when unchanged, `{ inheritTravellers: true }`, or `{ travellerIds: [...] }`
  - `effectiveTravellers(state, inheritedIds)`

- [ ] **Step 1: Create `js/traveller-picker.js`**

```js
import { toggleId } from './selection.js';

/**
 * "Who's going": which travel buddies a destination, checklist item or plan is
 * for. Three states, mirroring travellerIds on the API (see Travellers there):
 *   inherit  — not set, same as the parent (a checklist item's destination,
 *              a plan's checklist item). Stored as null.
 *   everyone — explicitly the whole trip. Stored as [].
 *   named    — just these buddies. Stored as their ids.
 * A destination's parent is the trip itself, so for it "inherit" and
 * "everyone" mean the same thing and the form only offers Everyone.
 */

export function travellersFromRecord(record, rootIsTrip = false) {
  const ids = record?.travellerIds;
  if (ids == null) return rootIsTrip ? { mode: 'everyone', ids: [], wasUnset: true } : { mode: 'inherit', ids: [] };
  return { mode: ids.length ? 'named' : 'everyone', ids: [...ids] };
}

export function newTravellers(rootIsTrip = false) {
  return rootIsTrip ? { mode: 'everyone', ids: [], wasUnset: true } : { mode: 'inherit', ids: [] };
}

/** "Choose…": start from what it was inheriting, so nothing is lost by opening it. */
export function chooseTravellers(state, inheritedIds) {
  state.ids = [...(inheritedIds || [])];
  state.mode = state.ids.length ? 'named' : 'everyone';
}

/** "Back to Cusco's". */
export function followParent(state) {
  state.mode = 'inherit';
  state.ids.splice(0);
}

export function toggleTraveller(state, userId) {
  toggleId(state.ids, userId);
  state.mode = state.ids.length ? 'named' : 'everyone';
}

export function everyoneGoes(state) {
  state.ids.splice(0);
  state.mode = 'everyone';
}

/** Who it is for right now, [] meaning the whole trip. */
export function effectiveTravellers(state, inheritedIds) {
  if (state.mode === 'inherit') return [...(inheritedIds || [])];
  return state.mode === 'everyone' ? [] : [...state.ids];
}

/**
 * What to send. Nothing when unchanged, so saving an unrelated edit never
 * turns "not set" into "[]" — a destination opened and saved must not stop
 * being unset merely because the form showed "Everyone".
 */
export function travellersPayload(state, initial) {
  const same = initial && state.mode === initial.mode
    && state.ids.length === initial.ids.length
    && state.ids.every((id, i) => id === initial.ids[i]);
  if (same) return {};
  if (state.mode === 'inherit') return { inheritTravellers: true };
  return { travellerIds: state.mode === 'everyone' ? [] : [...state.ids] };
}
```

- [ ] **Step 2: Wire the forms' state and payloads**

In each of `destinations.js`, `checklist.js` and `itinerary.js`, import what it uses from `'../../traveller-picker.js'`, and expose `chooseTravellers`, `followParent`, `toggleTraveller`, `everyoneGoes` and `effectiveTravellers` as methods on the returned tab object, the same way `budget.js` exposes `shareWithEveryone`.

`destinations.js`:
- In `blankForm()` add `travellers: newTravellers(true), travellersInitial: newTravellers(true),`.
- Where an existing destination opens for editing (`this.destForm = {`), add `travellers: travellersFromRecord(destination, true), travellersInitial: travellersFromRecord(destination, true),`, using that function's variable for the destination.
- In both save calls, spread into the payload: `...travellersPayload(this.destForm.travellers, this.destForm.travellersInitial),`.
- After a successful save and `reload()`, if `this.isHiddenByScope('destinations', savedId)` is true, show `toast.success("Saved. It's not on your list, so it shows under Whole trip.")` instead of the usual toast. For an update, `savedId` is `this.destForm.id`; for a create, it's `created.destination.id`.

`checklist.js`:
- `newCheck` state gets `travellers: newTravellers(), travellersInitial: newTravellers()`, and the `addCheck` payload spreads `...travellersPayload(this.newCheck.travellers, this.newCheck.travellersInitial)`.
- `drawerForm`, when an item opens (`this.openItem = item`), gets `travellers: travellersFromRecord(item), travellersInitial: travellersFromRecord(item)`, and the `updateCheck` payload spreads `...travellersPayload(this.drawerForm.travellers, this.drawerForm.travellersInitial)`.
- Add, beside the drawer code:

```js
    /** What a checklist item follows while unset: its destination's resolved list. */
    checklistInherited(item) {
      const from = item?.seededFromDestinationId;
      return from ? ((this.namedTravellers.destinations || {})[from] || []) : [];
    },
    checklistParentLabel(item) {
      const from = item?.seededFromDestinationId;
      const destination = from && this.destinations.find((d) => d.id === from);
      return destination ? destination.name : 'the trip';
    },
```

- `planForm`, in `blankPlan()` and the edit branch (`this.planForm = {`): add `travellers` / `travellersInitial` from `newTravellers()` or `travellersFromRecord(plan)`, and `sharedTouched: false` for a new plan (`true` when editing a plan that already has a budget row).
- The Shared by chips' `@click` for the plan form also sets `planForm.sharedTouched = true`.
- Both plan payloads spread `...travellersPayload(this.planForm.travellers, this.planForm.travellersInitial)`.
- In the `addPlan` payload, replace `costSharedByUserIds: this.planForm.sharedByUserIds,` with:

```js
            // Untouched, the server shares the new cost by the plan's own
            // travellers (see ItineraryService) — which is also what the chips
            // are showing, so what you see is what is saved.
            ...(this.planForm.sharedTouched ? { costSharedByUserIds: this.planForm.sharedByUserIds } : {}),
```

- Add:

```js
    planInherited() {
      return (this.namedTravellers.checklist || {})[this.planForm.checklistItemId || this.openItem?.id] || [];
    },
    /** Shared by, while untouched, mirrors who the plan is for. */
    planSharersShown() {
      return this.planForm.sharedTouched
        ? this.planForm.sharedByUserIds
        : this.effectiveTravellers(this.planForm.travellers, this.planInherited());
    },
```

In `trip.html`, the plan form's Shared by chips bind `:aria-pressed` to `planSharersShown().includes(person.userId)`. Their `@click` first copies `planSharersShown()` into `planForm.sharedByUserIds` when untouched, then toggles:

```html
@click="if (!planForm.sharedTouched) { planForm.sharedByUserIds.splice(0, planForm.sharedByUserIds.length, ...planSharersShown()); planForm.sharedTouched = true; } toggleSharer(planForm.sharedByUserIds, person.userId)"
```

`itinerary.js`, `entryForm`: the same as `planForm`. Add `travellers` / `travellersInitial`, spread the payload, and add `entryInherited()` (from `(this.namedTravellers.checklist || {})[this.entryForm.checklistItemId] || []`). The entry form's cost Shared by uses the same `sharedTouched` rule, with `entrySharersShown()` defined like `planSharersShown()`.

**A stay's later day:** when `this.entryForm.planId` is set (the entry being edited has one), the form shows no picker, only the note, and the payload never includes travellers.

- [ ] **Step 3: Add the picker markup (`trip.html`)**

**Destination form**, directly after the "Suppress auto-generated checklist" field:

```html
          <div class="field">
            <label id="dest-going-label">Who's going</label>
            <div class="chip-group" role="group" aria-labelledby="dest-going-label">
              <button type="button" class="chip" :aria-pressed="destForm.travellers.mode !== 'named'"
                      @click="everyoneGoes(destForm.travellers)">Everyone</button>
              <template x-for="person in tripSharers" :key="person.userId">
                <button type="button" class="chip" :aria-pressed="destForm.travellers.ids.includes(person.userId)"
                        @click="toggleTraveller(destForm.travellers, person.userId)" x-text="person.label"></button>
              </template>
            </div>
          </div>
```

**Checklist add form** (after the `new-check-note` field), **drawer** (after the `drawerForm.countryCodes` chips' field), **plan form** (directly before `id="plan-shared-label"`'s field) and **entry form** (directly before `id="entry-shared-label"`'s field): one block each. Here is the drawer's; the others substitute `newCheck` with `checklistInherited(null)` / `'the trip'`, `planForm` with `planInherited()` / the checklist item's description, and `entryForm` with `entryInherited()`:

```html
            <div class="field">
              <label id="drawer-going-label">Who's going</label>
              <p class="field-hint" x-show="drawerForm.travellers.mode === 'inherit'">
                Same as <span x-text="checklistParentLabel(openItem)"></span>:
                <span x-text="namesOf(checklistInherited(openItem)) || 'everyone'"></span>
                <button type="button" class="btn btn-ghost btn-sm"
                        @click="chooseTravellers(drawerForm.travellers, checklistInherited(openItem))">Choose…</button>
              </p>
              <div class="chip-group" role="group" aria-labelledby="drawer-going-label"
                   x-show="drawerForm.travellers.mode !== 'inherit'">
                <button type="button" class="chip" :aria-pressed="drawerForm.travellers.mode === 'everyone'"
                        @click="everyoneGoes(drawerForm.travellers)">Everyone</button>
                <template x-for="person in tripSharers" :key="person.userId">
                  <button type="button" class="chip" :aria-pressed="drawerForm.travellers.ids.includes(person.userId)"
                          @click="toggleTraveller(drawerForm.travellers, person.userId)" x-text="person.label"></button>
                </template>
                <button type="button" class="btn btn-ghost btn-sm" @click="followParent(drawerForm.travellers)">
                  Back to <span x-text="checklistParentLabel(openItem)"></span>'s</button>
              </div>
            </div>
```

Each block's `id` / `aria-labelledby` pair must be unique: `new-check-going-label`, `drawer-going-label`, `plan-going-label`, `entry-going-label`.

**Entry form, for a later day of a stay:** wrap its block in `x-show="!entryForm.planId"`, and add after it:

```html
            <p class="field-hint" x-show="entryForm.planId">Who's going follows the booking.</p>
```

- [ ] **Step 4: Chips on rows (`trip.html`)**

In the destination row, the checklist row and the itinerary entry, after the description text, add one chip each (substituting the kind and the loop variable):

```html
<span class="badge" x-show="travellerNames('destinations', destination.id)"
      x-text="'👥 ' + travellerNames('destinations', destination.id)"></span>
```

- [ ] **Step 5: Check it in the browser (scratch data)**

Check that:
- A destination for one buddy shows **👥 name**, and its seeded items show the same chip.
- The drawer says "Same as Cusco: …". **Choose…** starts from those names, and **Back to Cusco's** restores following, which survives Save and reload.
- A plan made from a Cusco item has cost **Shared by** pre-pressed with Cusco's buddies, and the saved budget row is shared by them.
- After the plan is saved, changing Cusco's buddies leaves that budget row's Shared by as it was.
- Editing a stay's later night shows "Who's going follows the booking".
- Saving a destination for someone else while in Mine shows the "not on your list" toast.
- Opening a destination and saving an unrelated change sends no `travellerIds`. Check the request in the browser's network panel.
- It works at phone width.

- [ ] **Step 6: Checkpoint.** Leave the changes uncommitted.

---

### Task 9: Documentation and the final full check

**Files:**
- Modify: `CLAUDE.md` (a new rule under *The cascade*)
- Modify: `README.md` (one line in *How a trip comes together*)

- [ ] **Step 1: Add the CLAUDE.md rule**, as a new bullet at the end of *The cascade*'s rule list:

```markdown
- **Who's going is inherited; money is copied once.** Destinations,
  checklist items and plans carry an optional `travellerIds`, resolved in one
  place, `trips/api/Travellers`: a destination names buddies or is the whole
  trip; a checklist item follows the destination it was seeded from; a plan
  follows its checklist item; a stay's later days always follow their plan.
  Three states, and the first two differ: null is "not set, follow the parent"
  (so a later edit to Cusco carries down to everything nobody edited), `[]` is
  explicitly the whole trip. That is why `traveller_ids` is a **nullable**
  `text[]` with no default, unlike `shared_by_user_ids` — and why
  `JdbcValues.nullableTextArray` exists. An empty *resolved* list means the
  whole trip, as with Shared by. The page is sent `mine` and `travellers` and
  never re-derives them. A plan's first cost is shared by the plan's travellers
  when the form sends no sharers, and never follows afterwards: moving money
  between buddies silently is the one thing this must not do. Focus view, not
  privacy — every member is still sent every row; only a personal published
  page leaves the others' parts out of the file.
```

- [ ] **Step 2: Add the README line**, as a new step after "Add travel buddies":

```markdown
3. **Say who's going.** A destination can be for some travel buddies only, and
   its checklist and plans follow it unless you change them. Each buddy sees
   **Mine** by default and can switch to **Whole trip**.
```

Renumber the steps after it.

- [ ] **Step 3: Run the whole suite, one last time**

Run the full suite and the skipped check.
Expected: BUILD SUCCESSFUL, `skipped="0"`.

- [ ] **Step 4: Clean up**

Stop the demo API (`lsof -ti tcp:8080 -sTCP:LISTEN | xargs -r kill`). Delete any screenshots this work added under `.playwright-mcp/`. Leave the user's own servers on 8080 and 3000 running if they are up.

- [ ] **Step 5: Final checkpoint.** Leave everything uncommitted and list the changed files for the user.
