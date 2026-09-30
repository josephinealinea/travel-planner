# Pending itinerary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let an itinerary entry (or a checklist "Plan" booking) be a Pending proposal instead of a settled plan, with buddy approvals, a creator-only finalize action, a Show-bar filter, and exclusion from every published page.

**Architecture:** A new `status` (`FINAL`/`PENDING`, default `FINAL`) and `approvedByUserIds` field on `ItineraryItem`, mirroring `BudgetStatus`'s null-safe default pattern exactly. All writes to either field act on the whole "plan group" (a stay's owning row plus every night it spans), never on one row alone. Two new endpoints (`PATCH .../status`, `POST`/`DELETE .../approve`) sit beside the existing create/update routes. The frontend adds toggle chips to the Itinerary tab's Show bar, a Pending pill/approve button/finalize checkbox to each row, and a "This itinerary plan is Final" checkbox to both booking forms.

**Tech Stack:** Spring Boot 3.5 / Java 21 (planner-api), vanilla JS + Alpine.js + Sass (planner-web), Flyway/Postgres for database mode, YAML for local mode.

**Spec:** `.claude/specs/2026-09-28-pending-itinerary-design.md`

## Global Constraints

- `status` defaults to `FINAL` everywhere a record predates the field — never `PENDING` — same reasoning as `BudgetStatus.CONFIRMED`.
- Every status/approval write applies to the **whole plan group** (owning row + every row whose `planId` points at it), never to one night alone.
- `PATCH /itinerary/{id}/status` only ever sets `FINAL`; there is no route back to `PENDING` except editing the form and unchecking its box.
- No enum `CHECK` constraint in SQL — matches this codebase's "enums as text" rule.
- No notifications (email/toast-to-others) for approvals or finalization — pull only.
- A Pending entry must never appear in any published file — trip page or any member's personal page — verified by whole-file search, not by markup inspection.
- New i18n keys go in both `planner-web/js/i18n/en.js` (alphabetical within their `trip.`/`itinerary.`/`checklist.` block) and, for API-thrown errors, `planner-api/src/main/resources/messages_en.properties`.
- Regenerate `docs/api/openapi.yaml` (`cd planner-api && ./gradlew openApiUpdate`) after the controller changes, per this repo's own rule that the committed spec must never go stale.

## Review Focus

- **Editing someone else's Pending plan through the API directly** (not through the form, which simply hides the field) — a member who is not the creator sends `status` on a PATCH anyway. The service must ignore/reject it, not silently trust the request body, or the frontend's hiding of the checkbox is the only thing stopping a non-creator from finalizing or un-finalizing someone else's proposal.
- **A stay with no other resolved participant besides the creator** — approving must never auto-finalize it (there is nobody left to approve), and the creator must not be double-counted into "everyone approved" by their own approve click.
- **A departed member who already approved** — `approvedByUserIds` may hold a user id no longer on the trip (mirrors "Former member" elsewhere). The count and hover-name list must not crash resolving a missing member, and that stale entry must not block a currently-correct "everyone approved" check from ever completing (recompute against *current* resolved participants, not against whoever approved historically).
- **Approving twice, or unapproving when never approved** — both must be no-ops rather than errors or duplicate list entries.
- **A single night with no `planId` and no later days** — `planGroupOf` must return exactly that one row, not throw or return an empty list, so the one-night case needs no special-casing anywhere it's used.

---

## Task 1: `ItineraryStatus` enum and `ItineraryItem` fields

**Files:**
- Create: `planner-api/src/main/java/com/josephinealinea/planner/itinerary/domain/ItineraryStatus.java`
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/itinerary/domain/ItineraryItem.java`
- Test: `planner-api/src/test/java/com/josephinealinea/planner/itinerary/ItineraryStatusTest.java`

**Interfaces:**
- Produces: `ItineraryStatus` enum (`FINAL`, `PENDING`); `ItineraryItem.getStatus()`/`setStatus(ItineraryStatus)` (null-safe, defaults to `FINAL`); `ItineraryItem.getApprovedByUserIds()`/`setApprovedByUserIds(List<String>)` (null-safe, defaults to empty list, defensive copy like `countryCodes`).

- [ ] **Step 1: Write the failing test**

```java
package com.josephinealinea.planner.itinerary;

import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.itinerary.domain.ItineraryStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ItineraryStatusTest {

    @Test
    void aFreshItemDefaultsToFinal() {
        assertThat(new ItineraryItem().getStatus()).isEqualTo(ItineraryStatus.FINAL);
    }

    @Test
    void settingNullReadsAsFinal() {
        ItineraryItem item = new ItineraryItem();
        item.setStatus(ItineraryStatus.PENDING);
        item.setStatus(null);
        assertThat(item.getStatus()).isEqualTo(ItineraryStatus.FINAL);
    }

    @Test
    void approvedByUserIdsDefaultsToAnEmptyMutableCopy() {
        ItineraryItem item = new ItineraryItem();
        assertThat(item.getApprovedByUserIds()).isEmpty();

        item.setApprovedByUserIds(List.of("user-ana"));
        assertThat(item.getApprovedByUserIds()).containsExactly("user-ana");

        item.setApprovedByUserIds(null);
        assertThat(item.getApprovedByUserIds()).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd planner-api && ./gradlew test --tests 'ItineraryStatusTest'`
Expected: FAIL — compile error, `ItineraryStatus` and the new accessors don't exist yet.

- [ ] **Step 3: Write the enum and the fields**

`ItineraryStatus.java`:

```java
package com.josephinealinea.planner.itinerary.domain;

/**
 * Whether an itinerary entry (or the plan it belongs to) is a settled plan or
 * still a proposal — "suggested to the herd" rather than decided.
 *
 * Mirrors budget.domain.BudgetStatus exactly, for the same reason: every row
 * written before this field existed was somebody's actual plan, never a
 * proposal, so a record with no status in its YAML must read as FINAL.
 */
public enum ItineraryStatus {
    FINAL,
    PENDING
}
```

In `ItineraryItem.java`, add the imports and fields (alongside the existing
`travellerIds`/`flight` fields near the bottom of the field list):

```java
import java.util.List;
// (already imported: ArrayList, List)
```

```java
    private ItineraryStatus status = ItineraryStatus.FINAL;

    /**
     * Who has clicked Approve, in click order — including the creator, if they
     * clicked it too. Purely a tally: see ItineraryService.approve for what it
     * does and does not trigger.
     */
    private List<String> approvedByUserIds = new ArrayList<>();
```

And the accessors, next to `getFlight`/`setFlight`:

```java
    public ItineraryStatus getStatus() { return status; }
    /** A record whose YAML predates this field has no status; that is a settled plan. */
    public void setStatus(ItineraryStatus status) {
        this.status = status == null ? ItineraryStatus.FINAL : status;
    }

    public List<String> getApprovedByUserIds() { return approvedByUserIds; }
    public void setApprovedByUserIds(List<String> approvedByUserIds) {
        this.approvedByUserIds = approvedByUserIds == null ? new ArrayList<>() : new ArrayList<>(approvedByUserIds);
    }
```

Add the import at the top of `ItineraryItem.java`:

```java
import com.josephinealinea.planner.itinerary.domain.ItineraryStatus;
```

(Not actually needed — `ItineraryStatus` is in the same package as `ItineraryItem`, so no import is required. Skip this line.)

- [ ] **Step 4: Run test to verify it passes**

Run: `cd planner-api && ./gradlew test --tests 'ItineraryStatusTest'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add planner-api/src/main/java/com/josephinealinea/planner/itinerary/domain/ItineraryStatus.java \
        planner-api/src/main/java/com/josephinealinea/planner/itinerary/domain/ItineraryItem.java \
        planner-api/src/test/java/com/josephinealinea/planner/itinerary/ItineraryStatusTest.java
git commit -m "Add ItineraryStatus and approvedByUserIds to ItineraryItem"
```

---

## Task 2: Storage — V15 migration, JDBC mapping, repository contract fixture

**Files:**
- Create: `planner-api/src/main/resources/db/migration/V15__itinerary_status.sql`
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/itinerary/infra/JdbcItineraryRepository.java`
- Modify: `planner-api/src/test/java/com/josephinealinea/planner/itinerary/ItineraryRepositoryContract.java`

**Interfaces:**
- Consumes: `ItineraryItem.getStatus()`/`setStatus`, `getApprovedByUserIds()`/`setApprovedByUserIds` (Task 1).
- Produces: `status`/`approved_by_user_ids` columns readable and writable in database mode; `ItineraryRepositoryContract.fullyPopulated()` now includes both, so every subclass's round-trip test (`YamlItineraryRepositoryTest`, `JdbcItineraryRepositoryTest`) exercises them.

- [ ] **Step 1: Write the failing assertion**

Modify `fullyPopulated()` in `ItineraryRepositoryContract.java` to set both new
fields (append before the final `return item;`):

```java
        item.setStatus(com.josephinealinea.planner.itinerary.domain.ItineraryStatus.PENDING);
        item.setApprovedByUserIds(List.of("user-ana"));
        return item;
```

This alone makes `everyFieldComesBackAsItWasSaved` fail for both YAML and
JDBC subclasses (YAML fails only because the JSON dump would still round-trip
fine via Jackson — the fixture is exercising *your test*, not YAML storage; the
JDBC subclass fails because the column doesn't exist yet).

- [ ] **Step 2: Run test to verify it fails**

Run: `cd planner-api && ./gradlew test --tests 'JdbcItineraryRepositoryTest'`
Expected: FAIL — `PSQLException: column "status" of relation "itinerary_items" does not exist` (or similar), since Testcontainers boots a real Postgres from the current migrations.

- [ ] **Step 3: Add the migration**

```sql
-- Pending itinerary proposals: FINAL is the default, matching every row
-- written before this field existed. approved_by_user_ids is an ordered
-- tally of who has clicked Approve, including the creator.
ALTER TABLE itinerary_items ADD COLUMN status text;
ALTER TABLE itinerary_items ADD COLUMN approved_by_user_ids text[];
```

- [ ] **Step 4: Map the columns in `JdbcItineraryRepository`**

Add `"status"`, `"approved_by_user_ids"` to the column list passed to the
superclass constructor:

```java
        super(jdbc, transactionManager, "itinerary_items",
                List.of("checklist_item_id", "plan_id", "category", "description", "note",
                        "start_at", "end_at", "all_day", "cost", "currency", "budget_item_id",
                        "country_codes", "sort_order",
                        "created_at", "created_by_user_id", "updated_at", "updated_by_user_id", "traveller_ids",
                        "flight", "status", "approved_by_user_ids"),
                Set.of("flight"),
                ItineraryItem::getId);
```

In `parametersOf`, add (near the other `values.put` calls, before `flight`):

```java
        values.put("status", JdbcValues.enumName(
                item.getStatus() == null ? ItineraryStatus.FINAL : item.getStatus()));
        values.put("approved_by_user_ids", JdbcValues.textArray(item.getApprovedByUserIds()));
```

In `mapRow`, add (after `item.setFlight(...)`):

```java
        item.setStatus(JdbcValues.enumValue(rs, "status", ItineraryStatus.class, ItineraryStatus.FINAL));
        item.setApprovedByUserIds(JdbcValues.textList(rs, "approved_by_user_ids"));
```

Add the import:

```java
import com.josephinealinea.planner.itinerary.domain.ItineraryStatus;
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd planner-api && ./gradlew test --tests 'JdbcItineraryRepositoryTest' --tests 'YamlItineraryRepositoryTest'`
Expected: PASS. If Testcontainers reports the Postgres tests **skipped** rather than run, stop and check `~/.testcontainers.properties`/`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` per this repo's own note in CLAUDE.md before trusting a green build.

- [ ] **Step 6: Commit**

```bash
git add planner-api/src/main/resources/db/migration/V15__itinerary_status.sql \
        planner-api/src/main/java/com/josephinealinea/planner/itinerary/infra/JdbcItineraryRepository.java \
        planner-api/src/test/java/com/josephinealinea/planner/itinerary/ItineraryRepositoryContract.java
git commit -m "Store itinerary status and approvals in both YAML and Postgres"
```

---

## Task 3: `ItineraryService.planGroupOf` and threading `status` through create/update

**Files:**
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/itinerary/api/ItineraryService.java`
- Test: `planner-api/src/test/java/com/josephinealinea/planner/itinerary/ItineraryServiceStatusTest.java`

**Interfaces:**
- Consumes: `ItineraryItem.getStatus/setStatus`, `ItineraryRepository.findAll`/`save`/`saveAll` if present (check: use `itinerary.save` per-row, matching `adoptOrphanedDays`'s own style, since there is no bulk `saveAll` visible on the interface used elsewhere in this file — confirm by using the same `itinerary.save(trip.getSlug(), row)` per-row loop `adoptOrphanedDays` already uses).
- Produces: `ItineraryService.planGroupOf(Trip, ItineraryItem)` returning `List<ItineraryItem>` (owning row first, then its days in whatever order `findAll` gives — callers that need date order already re-sort); `ItineraryService.Input` gains a `status` field as its new last positional component, with a backward-compatible constructor matching the current 16-arg shape.

- [ ] **Step 1: Write the failing test**

```java
package com.josephinealinea.planner.itinerary;

import com.josephinealinea.planner.budget.infra.BudgetRepository;
import com.josephinealinea.planner.budget.infra.YamlBudgetRepository;
import com.josephinealinea.planner.checklist.api.ChecklistService;
import com.josephinealinea.planner.checklist.domain.ChecklistCategory;
import com.josephinealinea.planner.checklist.infra.ChecklistRepository;
import com.josephinealinea.planner.checklist.infra.YamlChecklistRepository;
import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.destinations.api.TripCountries;
import com.josephinealinea.planner.destinations.infra.DestinationRepository;
import com.josephinealinea.planner.destinations.infra.YamlDestinationRepository;
import com.josephinealinea.planner.geocoding.CountryCatalog;
import com.josephinealinea.planner.itinerary.api.BudgetSync;
import com.josephinealinea.planner.itinerary.api.ItineraryService;
import com.josephinealinea.planner.itinerary.api.PlanTemplates;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.itinerary.domain.ItineraryStatus;
import com.josephinealinea.planner.itinerary.infra.ItineraryRepository;
import com.josephinealinea.planner.itinerary.infra.YamlItineraryRepository;
import com.josephinealinea.planner.storage.TripLocks;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import com.josephinealinea.planner.trips.api.TripAccessService;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.trips.domain.TripMember;
import com.josephinealinea.planner.trips.domain.TripRole;
import com.josephinealinea.planner.trips.infra.TripRepository;
import com.josephinealinea.planner.trips.infra.YamlTripRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ItineraryServiceStatusTest {

    private static final String SLUG = "latam-trip-2026";
    private static final String USER_ID = "user-1";

    private ItineraryService service;
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

        YamlStore store = new YamlStore();
        YamlPaths paths = new YamlPaths(props);
        TripLocks locks = new TripLocks();

        DestinationRepository destinations = new YamlDestinationRepository(store, paths, locks);
        TripCountries tripCountries = new TripCountries(destinations);
        ChecklistRepository checklistRepository = new YamlChecklistRepository(store, paths, locks);
        ItineraryRepository itineraryRepository = new YamlItineraryRepository(store, paths, locks);
        BudgetRepository budgetRepository = new YamlBudgetRepository(store, paths, locks);
        TripRepository trips = new YamlTripRepository(store, paths, locks);

        trip = new Trip();
        trip.setId("trip-1");
        trip.setSlug(SLUG);
        trip.setTitle("LATAM Trip 2026");
        trip.setOwnerUserId(USER_ID);
        trip.getMembers().add(new TripMember(USER_ID, TripRole.OWNER, null));
        trips.save(trip);

        TripAccessService access = new TripAccessService(trips);
        ChecklistService checklistService =
                new ChecklistService(checklistRepository, itineraryRepository, access, tripCountries);

        service = new ItineraryService(itineraryRepository, destinations, checklistService, access,
                new PlanTemplates(), new BudgetSync(budgetRepository), new CountryCatalog(RestClient.create()), tripCountries);
    }

    @Test
    void aNewEntryWithNoStatusDefaultsToFinal() {
        ItineraryItem plan = service.create(trip.getId(), USER_ID, inputWithStatus("Dinner", null, null));
        assertThat(plan.getStatus()).isEqualTo(ItineraryStatus.FINAL);
    }

    @Test
    void aNewEntryCanBeCreatedPending() {
        ItineraryItem plan = service.create(trip.getId(), USER_ID,
                inputWithStatus("Dinner", null, ItineraryStatus.PENDING));
        assertThat(plan.getStatus()).isEqualTo(ItineraryStatus.PENDING);
    }

    @Test
    void savingAStaysOwningRowPendingMarksEveryNightOfItPendingToo() {
        ItineraryService.Input stay = new ItineraryService.Input(null, ChecklistCategory.LODGING, "Hotel",
                LocalDateTime.of(2026, 10, 24, 15, 0), LocalDateTime.of(2026, 10, 26, 11, 0),
                null, null, null, null, null, null, null, null, null, null, null, ItineraryStatus.PENDING);
        ItineraryItem checkIn = service.create(trip.getId(), USER_ID, stay);

        List<ItineraryItem> all = service.list(trip.getId(), USER_ID);
        assertThat(all).allSatisfy(i -> assertThat(i.getStatus()).isEqualTo(ItineraryStatus.PENDING));
        assertThat(all.size()).isGreaterThan(1);
    }

    @Test
    void planGroupOfASingleNightWithNoStayIsJustItself() {
        ItineraryItem plan = service.create(trip.getId(), USER_ID, inputWithStatus("Dinner", null, null));
        assertThat(service.planGroupOf(trip, plan)).extracting(ItineraryItem::getId).containsExactly(plan.getId());
    }

    @Test
    void planGroupOfAStayIsTheOwningRowAndEveryNight() {
        ItineraryService.Input stay = new ItineraryService.Input(null, ChecklistCategory.LODGING, "Hotel",
                LocalDateTime.of(2026, 10, 24, 15, 0), LocalDateTime.of(2026, 10, 27, 11, 0),
                null, null, null, null, null, null, null, null, null, null, null, null);
        ItineraryItem checkIn = service.create(trip.getId(), USER_ID, stay);

        List<ItineraryItem> group = service.planGroupOf(trip, checkIn);
        assertThat(group).hasSize(3); // check-in + two more nights
        assertThat(group).extracting(ItineraryItem::getId).contains(checkIn.getId());

        // Asking from a later night's own row finds the same group.
        ItineraryItem night = group.stream().filter(i -> !i.getId().equals(checkIn.getId())).findFirst().orElseThrow();
        assertThat(service.planGroupOf(trip, night)).hasSize(3);
    }

    private ItineraryService.Input inputWithStatus(String description, ChecklistCategory category, ItineraryStatus status) {
        return new ItineraryService.Input(null, category, description, null, null, null, null, null,
                null, null, null, null, null, null, null, null, status);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd planner-api && ./gradlew test --tests 'ItineraryServiceStatusTest'`
Expected: FAIL — compile error, `Input`'s constructor doesn't take a 17th `status` argument yet and `planGroupOf` doesn't exist.

- [ ] **Step 3: Add `status` to `Input` and implement `planGroupOf`**

In `ItineraryService.Input`, add `ItineraryStatus status` as the new last
component of the canonical record, and add one more backward-compatible
constructor matching the *current* full 16-arg shape (the one ending in
`flight`), delegating with `status = null`:

```java
    public record Input(String checklistItemId,
                        ChecklistCategory category,
                        String description,
                        LocalDateTime startAt,
                        LocalDateTime endAt,
                        Boolean allDay,
                        BigDecimal cost,
                        String currency,
                        Boolean costCharged,
                        List<String> costSharedByUserIds,
                        String costPaidByUserId,
                        List<String> countryCodes,
                        List<String> travellerIds,
                        Boolean inheritTravellers,
                        String note,
                        FlightSnapshot flight,
                        /** Null on create means FINAL; null on a patch means "leave it". */
                        ItineraryStatus status) {

        /** The shape from before status existed: says nothing about it. */
        public Input(String checklistItemId, ChecklistCategory category, String description,
                     LocalDateTime startAt, LocalDateTime endAt, Boolean allDay, BigDecimal cost,
                     String currency, Boolean costCharged, List<String> costSharedByUserIds,
                     String costPaidByUserId, List<String> countryCodes,
                     List<String> travellerIds, Boolean inheritTravellers, String note,
                     FlightSnapshot flight) {
            this(checklistItemId, category, description, startAt, endAt, allDay, cost, currency,
                    costCharged, costSharedByUserIds, costPaidByUserId, countryCodes,
                    travellerIds, inheritTravellers, note, flight, null);
        }

        /** The shape from before flights existed: says nothing about them. */
        public Input(String checklistItemId, ChecklistCategory category, String description,
                     LocalDateTime startAt, LocalDateTime endAt, Boolean allDay, BigDecimal cost,
                     String currency, Boolean costCharged, List<String> costSharedByUserIds,
                     String costPaidByUserId, List<String> countryCodes,
                     List<String> travellerIds, Boolean inheritTravellers, String note) {
            this(checklistItemId, category, description, startAt, endAt, allDay, cost, currency,
                    costCharged, costSharedByUserIds, costPaidByUserId, countryCodes,
                    travellerIds, inheritTravellers, note, null, null);
        }

        /** The shape from before travellers existed: says nothing about them. */
        public Input(String checklistItemId, ChecklistCategory category, String description,
                     LocalDateTime startAt, LocalDateTime endAt, Boolean allDay, BigDecimal cost,
                     String currency, Boolean costCharged, List<String> costSharedByUserIds,
                     String costPaidByUserId, List<String> countryCodes) {
            this(checklistItemId, category, description, startAt, endAt, allDay, cost, currency,
                    costCharged, costSharedByUserIds, costPaidByUserId, countryCodes, null, null, null);
        }

        /** The shape from before note existed: says nothing about it. */
        public Input(String checklistItemId, ChecklistCategory category, String description,
                     LocalDateTime startAt, LocalDateTime endAt, Boolean allDay, BigDecimal cost,
                     String currency, Boolean costCharged, List<String> costSharedByUserIds,
                     String costPaidByUserId, List<String> countryCodes,
                     List<String> travellerIds, Boolean inheritTravellers) {
            this(checklistItemId, category, description, startAt, endAt, allDay, cost, currency,
                    costCharged, costSharedByUserIds, costPaidByUserId, countryCodes,
                    travellerIds, inheritTravellers, null);
        }
    }
```

Add the import:

```java
import com.josephinealinea.planner.itinerary.domain.ItineraryStatus;
```

In `create`, after `Audit.created(plan, userId);` and before the cost handling
(order doesn't matter, but keep it near the other simple field assignments —
right after `plan.setTravellerIds(...)`):

```java
        plan.setStatus(input.status());
```

In `update`, add alongside the other `if (input.x() != null)` blocks (near
`if (input.note() != null) ...`):

```java
        if (input.status() != null) plan.setStatus(input.status());
```

Then add `planGroupOf` as a public method, right after `require`. It takes
the `Trip` explicitly, matching every other repository lookup in this file
(`itinerary.findAll` is keyed by **slug**, never by `ItineraryItem.tripId` —
see `TripScopedRepository`'s "resolved from the slug, never from the entity"
rule in CLAUDE.md):

```java
    /**
     * The owning row plus every night it spans, however the row given belongs
     * to the group. A single night with no stay is its own one-element group,
     * so callers need no special case for "not a stay".
     */
    public List<ItineraryItem> planGroupOf(Trip trip, ItineraryItem item) {
        String owningId = item.ownsItsPlan() ? item.getId() : item.getPlanId();
        return itinerary.findAll(trip.getSlug()).stream()
                .filter(row -> row.getId().equals(owningId) || owningId.equals(row.getPlanId()))
                .toList();
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd planner-api && ./gradlew test --tests 'ItineraryServiceStatusTest'`
Expected: PASS

- [ ] **Step 5: Run the full existing itinerary test suite to catch any other caller of the old `Input` shape**

Run: `cd planner-api && ./gradlew test --tests 'com.josephinealinea.planner.itinerary.*'`
Expected: PASS. If any test constructs `Input` positionally with the old
16-argument list and now fails to compile because Java picked the new
17-arg canonical constructor ambiguously, add `null` as the 17th argument at
that call site (there is no ambiguity risk in practice: Java resolves by
arity, so a 16-argument call still binds the matching backward-compatible
constructor. This step is a safety net, not an expected failure).

- [ ] **Step 6: Commit**

```bash
git add planner-api/src/main/java/com/josephinealinea/planner/itinerary/api/ItineraryService.java \
        planner-api/src/test/java/com/josephinealinea/planner/itinerary/ItineraryServiceStatusTest.java
git commit -m "Thread itinerary status through create/update and add planGroupOf"
```

---

## Task 4: `ItineraryService.setStatus` and `approve`/`unapprove`

**Files:**
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/itinerary/api/ItineraryService.java`
- Test: `planner-api/src/test/java/com/josephinealinea/planner/itinerary/ItineraryServiceStatusTest.java` (same file as Task 3, more tests appended)
- Modify: `planner-api/src/main/resources/messages_en.properties`

**Interfaces:**
- Consumes: `Travellers.of(...)`, `Travellers.ofItineraryItem(item)` (existing), `TripMembers.of(trip).userIds()` (existing), `ApiException.forbidden`/`badRequest` (existing).
- Produces: `ItineraryService.setStatus(String tripId, String userId, String itemId, ItineraryStatus status)` (creator-only, refuses `PENDING`); `ItineraryService.approve(String tripId, String userId, String itemId)`; `ItineraryService.unapprove(String tripId, String userId, String itemId)`. Both return the group's owning `ItineraryItem` (the row a controller can hand back to the caller).

- [ ] **Step 1: Write the failing tests**

Append to `ItineraryServiceStatusTest.java`:

```java
    // ---- setStatus ----

    @Test
    void theCreatorCanFinalizeTheirOwnPendingEntry() {
        ItineraryItem plan = service.create(trip.getId(), USER_ID,
                inputWithStatus("Dinner", null, ItineraryStatus.PENDING));

        ItineraryItem updated = service.setStatus(trip.getId(), USER_ID, plan.getId(), ItineraryStatus.FINAL);

        assertThat(updated.getStatus()).isEqualTo(ItineraryStatus.FINAL);
    }

    @Test
    void someoneElseCannotFinalizeAnotherMembersPendingEntry() {
        trip.getMembers().add(new com.josephinealinea.planner.trips.domain.TripMember(
                "user-2", com.josephinealinea.planner.trips.domain.TripRole.MEMBER, USER_ID));
        ItineraryItem plan = service.create(trip.getId(), USER_ID,
                inputWithStatus("Dinner", null, ItineraryStatus.PENDING));

        assertThatThrownBy(() -> service.setStatus(trip.getId(), "user-2", plan.getId(), ItineraryStatus.FINAL))
                .isInstanceOf(com.josephinealinea.planner.shared.ApiException.class)
                .satisfies(ex -> assertThat(((com.josephinealinea.planner.shared.ApiException) ex).status().value()).isEqualTo(403));
    }

    @Test
    void setStatusRefusesToGoBackToPending() {
        ItineraryItem plan = service.create(trip.getId(), USER_ID, inputWithStatus("Dinner", null, null));

        assertThatThrownBy(() -> service.setStatus(trip.getId(), USER_ID, plan.getId(), ItineraryStatus.PENDING))
                .isInstanceOf(com.josephinealinea.planner.shared.ApiException.class)
                .satisfies(ex -> assertThat(((com.josephinealinea.planner.shared.ApiException) ex).status().value()).isEqualTo(400));
    }

    @Test
    void finalizingAStaysOwningRowFinalizesEveryNightOfIt() {
        ItineraryService.Input stay = new ItineraryService.Input(null, ChecklistCategory.LODGING, "Hotel",
                LocalDateTime.of(2026, 10, 24, 15, 0), LocalDateTime.of(2026, 10, 26, 11, 0),
                null, null, null, null, null, null, null, null, null, null, null, ItineraryStatus.PENDING);
        ItineraryItem checkIn = service.create(trip.getId(), USER_ID, stay);

        service.setStatus(trip.getId(), USER_ID, checkIn.getId(), ItineraryStatus.FINAL);

        assertThat(service.list(trip.getId(), USER_ID))
                .allSatisfy(i -> assertThat(i.getStatus()).isEqualTo(ItineraryStatus.FINAL));
    }

    // ---- approve / unapprove ----

    @Test
    void approvingTwiceIsANoOp() {
        ItineraryItem plan = service.create(trip.getId(), USER_ID,
                inputWithStatus("Dinner", null, ItineraryStatus.PENDING));

        service.approve(trip.getId(), USER_ID, plan.getId());
        ItineraryItem again = service.approve(trip.getId(), USER_ID, plan.getId());

        assertThat(again.getApprovedByUserIds()).containsExactly(USER_ID);
    }

    @Test
    void unapprovingSomeoneWhoNeverApprovedIsANoOp() {
        ItineraryItem plan = service.create(trip.getId(), USER_ID,
                inputWithStatus("Dinner", null, ItineraryStatus.PENDING));

        ItineraryItem result = service.unapprove(trip.getId(), USER_ID, plan.getId());

        assertThat(result.getApprovedByUserIds()).isEmpty();
    }

    @Test
    void approvingWithNoOtherParticipantNeverAutoFinalizes() {
        // Solo trip: the only resolved participant is the creator.
        ItineraryItem plan = service.create(trip.getId(), USER_ID,
                inputWithStatus("Dinner", null, ItineraryStatus.PENDING));

        ItineraryItem result = service.approve(trip.getId(), USER_ID, plan.getId());

        assertThat(result.getStatus()).isEqualTo(ItineraryStatus.PENDING);
    }

    @Test
    void onceEveryNonCreatorParticipantApprovesItAutoFinalizes() {
        trip.getMembers().add(new com.josephinealinea.planner.trips.domain.TripMember(
                "user-2", com.josephinealinea.planner.trips.domain.TripRole.MEMBER, USER_ID));
        ItineraryItem plan = service.create(trip.getId(), USER_ID,
                inputWithStatus("Dinner", null, ItineraryStatus.PENDING));

        ItineraryItem result = service.approve(trip.getId(), "user-2", plan.getId());

        assertThat(result.getStatus()).isEqualTo(ItineraryStatus.FINAL);
    }

    @Test
    void aDepartedApproverIsIgnoredByTheThresholdCheck() {
        trip.getMembers().add(new com.josephinealinea.planner.trips.domain.TripMember(
                "user-2", com.josephinealinea.planner.trips.domain.TripRole.MEMBER, USER_ID));
        trip.getMembers().add(new com.josephinealinea.planner.trips.domain.TripMember(
                "user-3", com.josephinealinea.planner.trips.domain.TripRole.MEMBER, USER_ID));
        ItineraryItem plan = service.create(trip.getId(), USER_ID,
                inputWithStatus("Dinner", null, ItineraryStatus.PENDING));
        service.approve(trip.getId(), "user-2", plan.getId());

        // user-3 leaves; only user-2 (already approved) remains besides the creator.
        trip.getMembers().removeIf(m -> m.getUserId().equals("user-3"));

        // Re-approving user-2 (a no-op on their own vote) should still resolve
        // "everyone but the creator has approved" against current membership.
        ItineraryItem result = service.approve(trip.getId(), "user-2", plan.getId());

        assertThat(result.getStatus()).isEqualTo(ItineraryStatus.FINAL);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd planner-api && ./gradlew test --tests 'ItineraryServiceStatusTest'`
Expected: FAIL — `setStatus`/`approve`/`unapprove` don't exist yet.

- [ ] **Step 3: Implement**

Add error keys to `messages_en.properties`, near the other `error.itinerary.*`
keys:

```properties
error.itinerary.statusOneDirectional=Pending is set by editing the entry and unchecking Mark as Final; this route only finalizes.
error.itinerary.notTheCreator=Only the member who created this belongs finalizes it directly.
error.itinerary.notAParticipant=Only a travel buddy this entry is for can approve it.
```

Add the three methods to `ItineraryService`, after `planGroupOf`:

```java
    /**
     * The only route back to FINAL from the list's quick action. Creator-only,
     * and one-directional: reversing to PENDING is only available by reopening
     * the form and unchecking its box, which is also how the creator restates
     * why. Applies to the whole plan group.
     */
    public ItineraryItem setStatus(String tripId, String userId, String itemId, ItineraryStatus status) {
        if (status != ItineraryStatus.FINAL) {
            throw ApiException.badRequest("error.itinerary.statusOneDirectional");
        }
        Trip trip = access.requireMember(tripId, userId);
        ItineraryItem item = require(trip, itemId);
        List<ItineraryItem> group = planGroupOf(trip, item);
        ItineraryItem owner = group.stream().filter(ItineraryItem::ownsItsPlan).findFirst().orElse(item);
        if (!userId.equals(owner.getCreatedByUserId())) {
            throw ApiException.forbidden("error.itinerary.notTheCreator");
        }

        group.forEach(row -> {
            row.setStatus(ItineraryStatus.FINAL);
            Audit.touched(row, userId);
            itinerary.save(trip.getSlug(), row);
        });
        return require(trip, itemId);
    }

    /** Adds the caller to the whole plan group's approvedByUserIds; a no-op if already there. */
    public ItineraryItem approve(String tripId, String userId, String itemId) {
        Trip trip = access.requireMember(tripId, userId);
        ItineraryItem item = require(trip, itemId);
        List<ItineraryItem> group = planGroupOf(trip, item);
        ItineraryItem owner = group.stream().filter(ItineraryItem::ownsItsPlan).findFirst().orElse(item);

        List<String> participants = resolvedParticipants(trip, owner);
        if (!participants.contains(userId)) {
            throw ApiException.forbidden("error.itinerary.notAParticipant");
        }

        boolean added = !owner.getApprovedByUserIds().contains(userId);
        group.forEach(row -> {
            if (!row.getApprovedByUserIds().contains(userId)) {
                row.getApprovedByUserIds().add(userId);
            }
            Audit.touched(row, userId);
            itinerary.save(trip.getSlug(), row);
        });

        if (added && owner.getStatus() == ItineraryStatus.PENDING) {
            List<String> requiredApprovers = participants.stream()
                    .filter(id -> !id.equals(owner.getCreatedByUserId()))
                    .toList();
            boolean everyoneApproved = !requiredApprovers.isEmpty()
                    && owner.getApprovedByUserIds().containsAll(requiredApprovers);
            if (everyoneApproved) {
                return setStatus(tripId, userId, itemId, ItineraryStatus.FINAL);
            }
        }
        return require(trip, itemId);
    }

    /** Removes the caller from the whole plan group's approvedByUserIds; never itself reopens a FINAL group. */
    public ItineraryItem unapprove(String tripId, String userId, String itemId) {
        Trip trip = access.requireMember(tripId, userId);
        ItineraryItem item = require(trip, itemId);
        List<ItineraryItem> group = planGroupOf(trip, item);

        group.forEach(row -> {
            if (row.getApprovedByUserIds().remove(userId)) {
                Audit.touched(row, userId);
                itinerary.save(trip.getSlug(), row);
            }
        });
        return require(trip, itemId);
    }

    /**
     * Who this entry resolves to, as explicit ids rather than the
     * empty-means-everyone shorthand Travellers.includes uses — approve needs
     * to iterate the actual set to check "has everyone approved".
     */
    private List<String> resolvedParticipants(Trip trip, ItineraryItem owner) {
        Travellers travellers = Travellers.of(trip, destinations.findAll(trip.getSlug()),
                checklistService.all(trip), itinerary.findAll(trip.getSlug()));
        List<String> resolved = travellers.ofItineraryItem(owner);
        return resolved.isEmpty() ? TripMembers.of(trip).userIds() : resolved;
    }
```

Confirm `ChecklistService.all(Trip)` exists (it is already used in
`defaultSharers` a few lines above — no new method needed).

- [ ] **Step 4: Run test to verify it passes**

Run: `cd planner-api && ./gradlew test --tests 'ItineraryServiceStatusTest'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add planner-api/src/main/java/com/josephinealinea/planner/itinerary/api/ItineraryService.java \
        planner-api/src/test/java/com/josephinealinea/planner/itinerary/ItineraryServiceStatusTest.java \
        planner-api/src/main/resources/messages_en.properties
git commit -m "Add itinerary setStatus, approve and unapprove"
```

---

## Task 5: Controller routes and OpenAPI regeneration

**Files:**
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/itinerary/web/ItineraryController.java`
- Modify: `docs/api/openapi.yaml` (generated, not hand-edited)
- Test: `planner-api/src/test/java/com/josephinealinea/planner/itinerary` — add a small web-layer test if this repo has one for `ItineraryController` already (check first: `find planner-api/src/test -iname '*ItineraryController*'`). If none exists, skip a dedicated controller test — the service tests in Task 3/4 already cover the behavior, and this repo's existing pattern (see `ItineraryServiceFlightTest`) tests through the service layer rather than the controller layer for this module.

**Interfaces:**
- Consumes: `ItineraryService.setStatus/approve/unapprove` (Task 4), `ItineraryService.Input`'s `status` component (Task 3).
- Produces: `PATCH /api/v1/trips/{tripId}/itinerary/{itemId}/status`, `POST`/`DELETE /api/v1/trips/{tripId}/itinerary/{itemId}/approve`.

- [ ] **Step 1: Add `status` to `CreateRequest`/`PatchRequest` and the two new routes**

In `CreateRequest`, add `ItineraryStatus status` as a new record component
(anywhere after `flight`) and thread it into `toInput()`:

```java
            FlightSnapshot flight,
            /** Null means FINAL by default. */
            ItineraryStatus status) {

        ItineraryService.Input toInput() {
            return new ItineraryService.Input(
                    checklistItemId, category, description, startAt, endAt, allDay, cost, currency,
                    costCharged, costSharedByUserIds, costPaidByUserId, countryCodes,
                    travellerIds, inheritTravellers, note, flight, status);
        }
    }
```

Do the same for `PatchRequest` (add `ItineraryStatus status` after `flight`,
same `toInput()` change).

Add a small request record and the two routes, after `update`:

```java
    public record StatusRequest(ItineraryStatus status) {}

    /** The list's quick "mark final" action — creator-only, one-directional. See ItineraryService.setStatus. */
    @PatchMapping("/{itemId}/status")
    ItineraryItem setStatus(@PathVariable String tripId,
                           @PathVariable String itemId,
                           @Valid @RequestBody StatusRequest request) {
        return itinerary.setStatus(tripId, currentUser.userId(), itemId, request.status());
    }

    @PostMapping("/{itemId}/approve")
    ItineraryItem approve(@PathVariable String tripId, @PathVariable String itemId) {
        return itinerary.approve(tripId, currentUser.userId(), itemId);
    }

    @DeleteMapping("/{itemId}/approve")
    ItineraryItem unapprove(@PathVariable String tripId, @PathVariable String itemId) {
        return itinerary.unapprove(tripId, currentUser.userId(), itemId);
    }
```

Add the import:

```java
import com.josephinealinea.planner.itinerary.domain.ItineraryStatus;
```

- [ ] **Step 2: Compile and run the full itinerary + checklist suites**

Run: `cd planner-api && ./gradlew test --tests 'com.josephinealinea.planner.itinerary.*' --tests 'com.josephinealinea.planner.checklist.*'`
Expected: PASS (this is a compile-safety check for the two-record change, not new behavior — Tasks 3–4 already tested the service).

- [ ] **Step 3: Regenerate the OpenAPI doc**

Run: `cd planner-api && ./gradlew openApiUpdate`
Expected: `docs/api/openapi.yaml` changes to include `status` on the
itinerary schemas and the two new paths. Run `cd planner-api && ./gradlew test --tests 'OpenApiDocTest'` to confirm it is no longer stale.

- [ ] **Step 4: Commit**

```bash
git add planner-api/src/main/java/com/josephinealinea/planner/itinerary/web/ItineraryController.java \
        docs/api/openapi.yaml
git commit -m "Expose itinerary status and approve/unapprove endpoints"
```

---

## Task 6: Exclude Pending entries from every published page

**Files:**
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/publish/api/StaticSiteRenderer.java`
- Test: `planner-api/src/test/java/com/josephinealinea/planner/publish/PersonalPageTest.java`

**Interfaces:**
- Consumes: `ItineraryItem.getStatus()`, `ItineraryStatus.FINAL` (Task 1).

- [ ] **Step 1: Write the failing test**

Add to `PersonalPageTest.java`, near `anotherMembersSpendingIsNowhereInThePersonalPageFile`:

```java
    @Test
    void aPendingEntryIsNowhereInAnyPublishedFile() throws Exception {
        com.josephinealinea.planner.itinerary.domain.ItineraryItem proposal =
                new com.josephinealinea.planner.itinerary.domain.ItineraryItem();
        proposal.setId("proposed-cusco-hike");
        proposal.setTripId(TRIP_ID);
        proposal.setDescription("Rainbow Mountain hike, unconfirmed");
        proposal.setStatus(com.josephinealinea.planner.itinerary.domain.ItineraryStatus.PENDING);
        itineraryRepo.save(SLUG, proposal);

        publish.publish(TRIP_ID, ALEX, "minima");

        assertThat(read(personal("alex"))).doesNotContain("Rainbow Mountain hike");
        assertThat(read(personal("sam"))).doesNotContain("Rainbow Mountain hike");
        assertThat(read(publishDir.resolve(SLUG).resolve("index.html"))).doesNotContain("Rainbow Mountain hike");
    }
```

This needs an `itineraryRepo` field the way `destinationsRepo` already is
one — add it:

```java
    private YamlItineraryRepository itineraryRepo;
```

and in `setUp`, change:

```java
        var itinerary = new YamlItineraryRepository(store, paths, locks);
```

to:

```java
        var itinerary = itineraryRepo = new YamlItineraryRepository(store, paths, locks);
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd planner-api && ./gradlew test --tests 'PersonalPageTest.aPendingEntryIsNowhereInAnyPublishedFile'`
Expected: FAIL — the entry currently appears in every published file.

- [ ] **Step 3: Filter in `StaticSiteRenderer.snapshot`**

Change:

```java
        var tripItinerary = itinerary.findAllOrdered(slug);
```

to:

```java
        // A proposal never has a public shape: filtered once, here, so every
        // reader downstream — trip page and every member's personal page —
        // sees only settled plans.
        var tripItinerary = itinerary.findAllOrdered(slug).stream()
                .filter(item -> item.getStatus() == ItineraryStatus.FINAL)
                .toList();
```

Add the import:

```java
import com.josephinealinea.planner.itinerary.domain.ItineraryStatus;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd planner-api && ./gradlew test --tests 'PersonalPageTest'`
Expected: PASS (the whole file, to confirm nothing else broke).

- [ ] **Step 5: Commit**

```bash
git add planner-api/src/main/java/com/josephinealinea/planner/publish/api/StaticSiteRenderer.java \
        planner-api/src/test/java/com/josephinealinea/planner/publish/PersonalPageTest.java
git commit -m "Exclude Pending itinerary entries from every published page"
```

---

## Task 7: Frontend i18n keys

**Files:**
- Modify: `planner-web/js/i18n/en.js`

**Interfaces:**
- Produces: every key the remaining frontend tasks reference by name, listed below so those tasks can call `t('...')` without guessing spelling.

- [ ] **Step 1: Add the keys**

Insert alphabetically within the existing blocks. Within the `checklist.*`
block, alongside the existing `checklist.describeThePlan` etc.:

```js
  'checklist.markPlanAsFinal': "This itinerary plan is Final",
  'checklist.proposedNote': "Will be suggested to the herd 🦙",
```

Within the `itinerary.*` block, alongside the existing `itinerary.itemsCount` etc.:

```js
  'itinerary.approvedByCount.one': ", approved by {count} buddy",
  'itinerary.approvedByCount.other': ", approved by {count} buddies",
  'itinerary.approve': "Approve",
  'itinerary.approveNamed': "Approve {name}",
  'itinerary.markAsFinal': "Mark as Final",
  'itinerary.markAsFinalBody': "Change this back to Pending using the Edit action.",
  'itinerary.markThisAsFinal': "Mark as Final?",
  'itinerary.markPlanAsFinal': "This itinerary plan is Final",
  'itinerary.pending': "Pending",
  'itinerary.proposedNote': "Will be suggested to the herd 🦙",
  'itinerary.suggestedBy': "Suggested by {name}",
  'itinerary.undoApproveNamed': "Undo approval for {name}",
```

Within the `trip.*` block, alongside the existing `trip.final`-adjacent keys
(there is no existing `trip.final`; insert alphabetically near
`trip.filter`/`trip.fromChecklistItem` if present, otherwise near
`trip.everyone`):

```js
  'trip.final': "Final",
```

(`trip.pending` already exists at `planner-web/js/i18n/en.js:495` — reuse it
for the Show-bar chip and the row pill; do not add a duplicate.)

- [ ] **Step 2: Run the i18n consistency check**

Run: `cd planner-web && npm run check`
Expected: PASS — every new key used by a later task must already exist here,
and this check is what `npm run check`'s `i18n-check.mjs` verifies (used
keys vs. defined keys), so run it again after Tasks 9–11 add the actual
`t('...')` call sites.

- [ ] **Step 3: Commit**

```bash
git add planner-web/js/i18n/en.js
git commit -m "Add i18n keys for pending itinerary proposals"
```

---

## Task 8: `js/api.js` client functions

**Files:**
- Modify: `planner-web/js/api.js`

**Interfaces:**
- Produces: `api.setItineraryStatus(tripId, itemId, status)`, `api.approveItinerary(tripId, itemId)`, `api.unapproveItinerary(tripId, itemId)`.

- [ ] **Step 1: Add the functions**

Next to the existing itinerary block:

```js
  setItineraryStatus: (id, itemId, status) => patch(`${trip(id)}/itinerary/${itemId}/status`, { status }),
  approveItinerary: (id, itemId) => post(`${trip(id)}/itinerary/${itemId}/approve`, {}),
  unapproveItinerary: (id, itemId) => del(`${trip(id)}/itinerary/${itemId}/approve`),
```

- [ ] **Step 2: Manual smoke check**

There is no frontend test suite in this repo (see CLAUDE.md). Defer
verification of these calls to Task 12's browser walkthrough, which exercises
every one of them through the UI.

- [ ] **Step 3: Commit**

```bash
git add planner-web/js/api.js
git commit -m "Add itinerary status/approve API client functions"
```

---

## Task 9: `itinerary.js` — filter state, row helpers, approve/finalize actions

**Files:**
- Modify: `planner-web/js/pages/trip/itinerary.js`

**Interfaces:**
- Consumes: `this.namedTravellers.itinerary` (existing, from `trip.js`), `this.members` (existing), `t()` (Task 7 keys), `this.api.setItineraryStatus/approveItinerary/unapproveItinerary` (Task 8).
- Produces: `itinShowFinal`, `itinShowPending` state; `filteredItinerary` gains the status predicate; `entryView` carries `status`/`approvedByUserIds`; `resolvedParticipantIds(item)`, `canApprove(item)`, `hasApproved(item)`, `approverNames(item)`, `approvedByLine(item)`, `isCreatorOf(item)`, `toggleApprove(item)`, `askFinalize(item)`, `confirmFinalize()`, `finalizingItem` state, and the Entry form's finalize checkbox wiring (`entryForm.markFinal`, visibility, payload).

- [ ] **Step 1: State and `entryView`/`blankEntry`/`openEditEntry` changes**

In `blankEntry()`, add:

```js
    // Defaulted checked, like the checklist Plan form — see savePlan/saveEntry.
    markFinal: true,
```

In `entryView(item)`, add the two new fields:

```js
function entryView(item) {
  return {
    item,
    key: item.id,
    startAt: item.allDay ? null : item.startAt,
    endAt: item.allDay ? null : item.endAt,
    cost: item.cost,
    currency: item.currency,
    status: item.status,
    approvedByUserIds: item.approvedByUserIds || [],
  };
}
```

Add new top-level state, alongside `itinShow`:

```js
    // Final and Pending toggle independently, alongside itinShow's own
    // ALL/WEATHER/ITINERARY selector — see filteredItinerary.
    itinShowFinal: true,
    itinShowPending: false,

    // The item a finalize confirmation is pending for — mirrors completingItem
    // on the checklist tab.
    finalizingItem: null,
```

In `openAddEntry()`, no change needed — `blankEntry()` already defaults
`markFinal: true`.

In `openEditEntry(item)`, add to the object literal:

```js
        markFinal: item.status !== 'PENDING',
```

- [ ] **Step 2: `filteredItinerary` predicate**

```js
    get filteredItinerary() {
      if (this.itinShow === 'WEATHER') return [];
      return this.scopedItinerary.filter((item) => {
        if (this.itinCategoryFilters.length
            && !this.itinCategoryFilters.includes(item.category)) return false;
        if (item.status === 'PENDING') return this.itinShowPending;
        return this.itinShowFinal;
      });
    },
```

- [ ] **Step 3: Weather Only resets the two toggles**

Wherever `itinShow` is set by the Show-bar click (this is in `trip.html`, not
here — `@click="itinShow = option"` — so add a method here for the template
to call instead):

```js
    setItinShow(option) {
      this.itinShow = option;
      if (option === 'WEATHER') {
        this.itinShowFinal = true;
        this.itinShowPending = false;
      }
    },
```

- [ ] **Step 4: Creator / participant / approval helpers**

```js
    /** The plan group's owning row's creator, resolved from the item itself. */
    isCreatorOf(item) {
      return !!this.currentUserId && item.createdByUserId === this.currentUserId;
    },

    /** Resolved participant ids for an entry — the whole trip when unset. See Travellers. */
    resolvedParticipantIds(item) {
      const named = (this.namedTravellers.itinerary || {})[item.id];
      return named && named.length ? named : this.members.map((m) => m.userId);
    },

    /** Only a resolved participant may approve — including the creator. */
    canApprove(item) {
      return item.status === 'PENDING' && this.resolvedParticipantIds(item).includes(this.currentUserId);
    },

    hasApproved(item) {
      return (item.approvedByUserIds || []).includes(this.currentUserId);
    },

    /** Display names for the hover tooltip; a departed member is left out rather than crashing. */
    approverNames(item) {
      return (item.approvedByUserIds || [])
        .map((id) => this.members.find((m) => m.userId === id)?.displayName)
        .filter(Boolean)
        .join(', ');
    },

    /** ", approved by N buddies" — empty string when nobody has yet. */
    approvedByCount(item) {
      const n = (item.approvedByUserIds || []).length;
      return n ? t('itinerary.approvedByCount', { count: n }) : '';
    },

    suggestedByLine(item) {
      const creator = this.members.find((m) => m.userId === item.createdByUserId)?.displayName
        || t('budget.formerBuddy');
      return t('itinerary.suggestedBy', { name: creator }) + this.approvedByCount(item);
    },

    async toggleApprove(item) {
      try {
        if (this.hasApproved(item)) await this.api.unapproveItinerary(this.trip.id, item.id);
        else await this.api.approveItinerary(this.trip.id, item.id);
        await this.reload();
      } catch (error) {
        toast.error(error.fullMessage);
      }
    },

    askFinalize(item) {
      this.finalizingItem = item;
    },

    async confirmFinalize() {
      const item = this.finalizingItem;
      this.finalizingItem = null;
      if (!item) return;
      try {
        await this.api.setItineraryStatus(this.trip.id, item.id, 'FINAL');
        toast.success(t('itinerary.entryUpdated'));
        await this.reload();
      } catch (error) {
        toast.error(error.fullMessage);
      }
    },
```

- [ ] **Step 5: `saveEntry` sends `status` only when the checkbox is rendered**

The form's visibility rule (new entry, or editing your own) is decided in the
template (Task 11), but the payload must only carry `status` when that
checkbox actually applies — otherwise a non-creator's edit (which never shows
the checkbox) could still silently flip status through a stray default. Add a
guard method:

```js
    /** Whether this form shows the This itinerary plan is Final checkbox at all. */
    entryShowsFinalCheckbox() {
      return !this.entryForm.id || this.isCreatorOf(
        this.itinerary.find((i) => i.id === this.entryForm.id) || {});
    },
```

In `saveEntry()`, change the payload's construction to conditionally include
`status`:

```js
        const payload = {
          category: this.entryForm.category,
          description,
          note: this.entryForm.note.trim() || null,
          startAt,
          endAt,
          allDay: !this.entryForm.startTime,
          currency: cost == null ? null : (this.entryForm.currency || null),
          costCharged: this.entryForm.costCharged,
          costSharedByUserIds: this.entrySharersShown(),
          costPaidByUserId: this.entryForm.paidByUserId || '',
          countryCodes: this.entryForm.countryCodes,
          ...this.flightPayload(this.entryForm, this.entryForm.category === 'TRANSPORTATION'),
          ...(this.entryForm.planId ? {} : travellersPayload(this.entryForm.travellers, this.entryForm.travellersInitial)),
          ...(this.entryShowsFinalCheckbox() ? { status: this.entryForm.markFinal ? 'FINAL' : 'PENDING' } : {}),
        };
```

- [ ] **Step 6: Manual smoke check**

No frontend test suite exists. Defer to Task 12.

- [ ] **Step 7: Commit**

```bash
git add planner-web/js/pages/trip/itinerary.js
git commit -m "Add pending-itinerary filter, approve and finalize logic to the Itinerary tab"
```

---

## Task 10: `checklist.js` — Plan form's finalize checkbox

**Files:**
- Modify: `planner-web/js/pages/trip/checklist.js`

**Interfaces:**
- Consumes: `t()` (Task 7 keys).
- Produces: `planForm.markFinal`; `planShowsFinalCheckbox()`; `status` folded into `savePlan()`'s payload the same way Task 9 did for `saveEntry()`.

- [ ] **Step 1: `blankPlan` and `editPlan`**

In `blankPlan()`, add:

```js
    // Defaulted checked — see savePlan and the itinerary Entry form's markFinal.
    markFinal: true,
```

In `editPlan(plan)`, add to the object literal:

```js
        markFinal: plan.status !== 'PENDING',
```

- [ ] **Step 2: Visibility helper**

```js
    /** Whether the Plan form shows the This itinerary plan is Final checkbox at all. */
    planShowsFinalCheckbox() {
      if (!this.planForm.id) return true;
      const plan = this.itinerary.find((i) => i.id === this.planForm.id);
      return !!plan && plan.createdByUserId === this.currentUserId;
    },
```

- [ ] **Step 3: Fold `status` into `savePlan()`'s two payloads**

In both branches of `savePlan()` (the `if (this.planForm.id)` update branch
and the `else` create branch), add the same conditional entry used in Task 9:

```js
            ...(this.planShowsFinalCheckbox() ? { status: this.planForm.markFinal ? 'FINAL' : 'PENDING' } : {}),
```

— placed at the end of each payload object literal, right after the existing
`...travellersPayload(...)` spread.

- [ ] **Step 4: Manual smoke check**

Deferred to Task 12.

- [ ] **Step 5: Commit**

```bash
git add planner-web/js/pages/trip/checklist.js
git commit -m "Add This itinerary plan is Final checkbox wiring to the checklist Plan form"
```

---

## Task 11: HTML — Show-bar chips, Pending row, both form checkboxes, finalize-confirm dialog

**Files:**
- Modify: `planner-web/trip.html`

**Interfaces:**
- Consumes: every state/method from Tasks 9–10.

- [ ] **Step 1: Show-bar toggle chips**

In the Itinerary tab's Show toolbar (around line 473–482), change the
`@click="itinShow = option"` to call the new method, and add the two
independent toggle chips after the existing `chip-group`:

```html
        <div class="toolbar">
          <span class="toolbar-label" id="itin-show-label" data-i18n="trip.show"></span>
          <div class="chip-group" role="group" aria-labelledby="itin-show-label">
            <template x-for="option in ['ALL', 'WEATHER', 'ITINERARY']" :key="option">
              <button type="button" class="chip" :aria-pressed="itinShow === option"
                      @click="setItinShow(option)"
                      x-text="option === 'ALL' ? $t('common.all') : (option === 'WEATHER' ? $t('trip.weatherOnly') : $t('trip.itineraryOnly'))"></button>
            </template>
          </div>
          <div class="chip-group" role="group" aria-label="Final / Pending">
            <button type="button" class="chip" :aria-pressed="itinShowFinal"
                    @click="itinShowFinal = !itinShowFinal" data-i18n="trip.final"></button>
            <button type="button" class="chip" :aria-pressed="itinShowPending"
                    @click="itinShowPending = !itinShowPending" data-i18n="trip.pending"></button>
          </div>
        </div>
```

- [ ] **Step 2: Pending row — pill, suggested-by line, approve button, finalize checkbox**

In the `timeline-entry` template (around line 560–598), add the finalize
checkbox before the existing bulk-select checkbox, the Pending pill after the
description, the suggested-by line, and the Approve/Undo button:

```html
              <template x-for="entry in day.entries" :key="entry.key">
                <div class="timeline-entry">
                  <label class="select-box select-box-tight" x-show="entry.item.status === 'PENDING' && isCreatorOf(entry.item)" @click.stop>
                    <input type="checkbox" :checked="false" @change="askFinalize(entry.item)"
                           :aria-label="$t('itinerary.markThisAsFinal')">
                  </label>
                  <label class="select-box select-box-tight">
                    <input type="checkbox" :checked="isItinSelected(entry.item.id)"
                           @change="toggleItinSelected(entry.item.id)"
                           :aria-label="$t('common.selectNamedWhen', { name: entry.item.description, when: planWhen(entry.item) })">
                  </label>
                  <span class="timeline-time" x-text="entryTime(entry)"></span>
                  <div class="timeline-body">
                    <div class="timeline-desc">
                      <span x-text="entry.item.description"></span>
                      <span class="chip chip-pending" x-show="entry.item.status === 'PENDING'" data-i18n="itinerary.pending"></span>
                    </div>
                    <div class="timeline-note tiny muted" x-show="entry.item.status === 'PENDING'" style="font-style:italic"
                         :title="approverNames(entry.item)" x-text="suggestedByLine(entry.item)"></div>
                    <div class="timeline-tags" x-show="entry.item.flight || travellerNameList('itinerary', entry.item.id).length" x-cloak>
                      <template x-for="pill in flightPills(entry.item.flight)" :key="pill"><span class="chip" x-text="pill"></span></template>
                      <template x-for="name in travellerNameList('itinerary', entry.item.id)" :key="name">
                        <span class="badge badge-traveller" x-text="'👥 ' + name"></span>
                      </template>
                    </div>
                    <div class="timeline-meta">
                      <span class="chip">
                        <span x-text="categoryOf(entry.item.category).icon"></span>
                        <span x-text="categoryOf(entry.item.category).label"></span>
                      </span>
                      <template x-for="label in countryLabels(entry.item.countryCodes)" :key="label">
                        <span class="chip" x-text="label"></span>
                      </template>
                      <span x-show="sourceChecklistLabel(entry.item)" x-text="sourceChecklistLabel(entry.item)"></span>
                    </div>
                    <div class="timeline-note" x-show="entry.item.note" x-text="entry.item.note"></div>
                  </div>
                  <span class="timeline-cost" x-show="entry.cost" x-text="entryCost(entry)"></span>
                  <span class="timeline-actions btn-row" :class="canApprove(entry.item) ? 'timeline-actions-pending' : ''">
                    <button type="button" class="btn btn-sm" @click="openEditEntry(entry.item)"
                            :aria-label="$t('common.editNamedWhen', { name: entry.item.description, when: planWhen(entry.item) })" data-i18n="trip.edit"></button>
                    <button type="button" class="btn btn-sm btn-primary" x-show="canApprove(entry.item)"
                            @click="toggleApprove(entry.item)"
                            :aria-label="$t(hasApproved(entry.item) ? 'itinerary.undoApproveNamed' : 'itinerary.approveNamed', { name: entry.item.description })"
                            x-text="hasApproved(entry.item) ? $t('trip.undo') : $t('itinerary.approve')"></button>
                  </span>
                </div>
              </template>
```

Note: the existing `timeline-desc` div previously held plain text
(`x-text="entry.item.description"`); it now wraps two spans, so its
`x-text` attribute is removed in favor of the inner spans above.

- [ ] **Step 3: Finalize confirmation dialog**

Add near the existing "Confirm completing a checklist item" dialog (after
line ~1899), a new sibling dialog:

```html
      <!-- Confirm finalizing a pending itinerary entry — the whole plan group moves together. -->
      <div class="modal-backdrop" x-show="finalizingItem" x-cloak
           @click.self="finalizingItem = null"
           @keydown.escape.window="finalizingItem = null">
        <div class="modal" role="dialog" aria-modal="true" aria-labelledby="finalize-itinerary-title"
             x-dialog="finalizingItem">
          <div class="modal-header">
            <h2 class="modal-title" id="finalize-itinerary-title" data-i18n="itinerary.markThisAsFinal"></h2>
            <button type="button" class="modal-close" @click="finalizingItem = null"
                    data-i18n-aria-label="common.close">✕</button>
          </div>
          <p class="small" data-i18n="itinerary.markAsFinalBody"></p>
          <div class="modal-footer">
            <button type="button" class="btn" @click="finalizingItem = null" data-i18n="common.cancel"></button>
            <button type="button" class="btn btn-primary"
                    @click="confirmFinalize()" data-i18n="itinerary.markAsFinal"></button>
          </div>
        </div>
      </div>
```

- [ ] **Step 4: Entry form's This itinerary plan is Final checkbox**

In the Entry form's `.modal-footer` (around line 1847–1851), change it to a
split footer with the checkbox on the left:

```html
            <div class="modal-footer modal-footer-split" x-show="entryShowsFinalCheckbox()">
              <div>
                <label class="checkbox">
                  <input type="checkbox" x-model="entryForm.markFinal">
                  <span data-i18n="checklist.markPlanAsFinal"></span>
                </label>
                <p class="field-hint" x-show="!entryForm.markFinal" data-i18n="itinerary.proposedNote"></p>
              </div>
              <span class="btn-row">
                <button type="button" class="btn" @click="entryOpen = false" data-i18n="common.cancel"></button>
                <button type="submit" class="btn btn-primary" :disabled="entryBusy"
                        x-text="entryBusy ? $t('common.saving') : $t('common.save')"></button>
              </span>
            </div>
            <div class="modal-footer" x-show="!entryShowsFinalCheckbox()">
              <button type="button" class="btn" @click="entryOpen = false" data-i18n="common.cancel"></button>
              <button type="submit" class="btn btn-primary" :disabled="entryBusy"
                      x-text="entryBusy ? $t('common.saving') : $t('common.save')"></button>
            </div>
```

- [ ] **Step 5: Plan form's This itinerary plan is Final checkbox**

In the Plan form's `.row.row-end` footer (around line 1316–1320), apply the
same split-footer pattern:

```html
                  <div class="modal-footer modal-footer-split" style="margin-top:12px" x-show="planShowsFinalCheckbox()">
                    <div>
                      <label class="checkbox">
                        <input type="checkbox" x-model="planForm.markFinal">
                        <span data-i18n="checklist.markPlanAsFinal"></span>
                      </label>
                      <p class="field-hint" x-show="!planForm.markFinal" data-i18n="checklist.proposedNote"></p>
                    </div>
                    <span class="btn-row">
                      <button type="button" class="btn" @click="planOpen = false" data-i18n="common.cancel"></button>
                      <button type="button" class="btn btn-primary" :disabled="planBusy"
                              @click="savePlan()" x-text="planBusy ? $t('common.saving') : $t('trip.savePlan')"></button>
                    </span>
                  </div>
                  <div class="row row-end" style="margin-top:12px" x-show="!planShowsFinalCheckbox()">
                    <button type="button" class="btn" @click="planOpen = false" data-i18n="common.cancel"></button>
                    <button type="button" class="btn btn-primary" :disabled="planBusy"
                            @click="savePlan()" x-text="planBusy ? $t('common.saving') : $t('trip.savePlan')"></button>
                  </div>
```

- [ ] **Step 6: Run the i18n check**

Run: `cd planner-web && npm run check`
Expected: PASS — every `data-i18n`/`$t(...)` call site added above must
resolve to a key from Task 7.

- [ ] **Step 7: Commit**

```bash
git add planner-web/trip.html
git commit -m "Add pending-itinerary UI: Show filter, row pill/approve, and both form checkboxes"
```

---

## Task 12: SCSS — stacking the Approve button, split footer already exists

**Files:**
- Modify: `planner-web/scss/components/_timeline.scss`
- Modify: `planner-web/scss/css` build output (via `npm run css`)

**Interfaces:**
- Consumes: `.timeline-actions-pending` class from Task 11's markup; `.modal-footer-split` already exists (`_modal.scss:67`) and needs no change.

- [ ] **Step 1: Add the stacking rule**

In `_timeline.scss`, after the existing `.timeline-actions { flex: none; }`:

```scss
// A Pending row's Approve button stacks below Edit rather than beside it —
// the two buttons answer different questions and a pill-row read as one
// action. Non-pending rows are unaffected: flex-direction on a single child
// changes nothing visible.
.timeline-actions.timeline-actions-pending {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: t.$space-2;
}
```

And inside the existing `@include m.mobile { ... }` block, add:

```scss
  // On a phone the pair drops beneath the row's own content instead of
  // riding the checkbox/time/Edit line, the same "actions drop below" shape
  // the settle table's Record payment button already uses.
  .timeline-actions.timeline-actions-pending {
    order: 5;
    flex: 1 1 100%;
    flex-direction: row;
    justify-content: flex-end;
    margin-top: t.$space-2;
  }
```

- [ ] **Step 2: Rebuild the stylesheets**

Run: `cd planner-web && npm run css`
Expected: succeeds with no Sass errors.

- [ ] **Step 3: Commit**

```bash
git add planner-web/scss/components/_timeline.scss planner-web/assets/css
git commit -m "Style the Pending row's Approve button stacking"
```

---

## Task 13: Manual browser verification

**Files:** none (verification only).

**Interfaces:** none.

- [ ] **Step 1: Start the stack**

```bash
cd planner-api && BOOTSTRAP_OWNER_EMAIL=you@example.com BOOTSTRAP_OWNER_PASSWORD=password123 ./gradlew bootRun
```

In a second terminal:

```bash
cd planner-web && npm run css && ./serve.sh
```

- [ ] **Step 2: Golden path**

1. Create a trip with two members (yourself and a second account, or use the
   trip's existing invite flow), so there is a non-creator to test approval
   with.
2. On the Checklist tab, open an item's Plan form. Confirm the "Mark plan as
   Final" checkbox is checked by default; uncheck it and confirm the italic
   note appears. Save.
3. On the Itinerary tab, confirm the new entry shows a Pending pill, the
   "Suggested by <you>" line, and — since you created it — the creator-only
   finalize checkbox to its left, but **no** Approve button (you're the only
   resolved participant, so `canApprove` and the auto-finalize threshold both
   correctly find nobody left to approve).
4. Toggle Show → Pending on, Final off: confirm the entry still shows (and a
   normal Final entry disappears). Toggle both on: confirm both kinds show.
   Click Weather Only: confirm Final/Pending silently reset to their
   defaults.
5. Add a second member as a resolved participant of that entry (or leave it
   trip-wide, which makes every current member a participant) and, signed in
   as that second member, confirm the Approve button appears, click it, and
   confirm the row auto-flips to Final and the pill disappears (re-toggle
   Show → Pending to make sure it's really gone, not just filtered out by
   Final still being on).
6. As the creator, open the entry's Edit form again and confirm the "Mark
   plan as Final" checkbox is now checked (reflecting FINAL) — and that
   editing an entry someone *else* created never shows the checkbox at all
   (create a second entry as the other member to check this).
7. Click the itinerary Entry form directly (not through a checklist item),
   uncheck Mark as Final, save, and confirm the same Pending behavior holds
   for entries added straight to the Itinerary tab.
8. Publish the trip. Confirm the Pending entry from step 2/7 is absent from
   both the trip's public page and every member's personal page (view page
   source, not just the rendered markup, per this project's own "not
   displayed must mean not shipped" testing note).

- [ ] **Step 3: Report results**

If every check above passes, the feature is done. If any step fails, note
which one and return to the corresponding task above rather than patching
ad hoc — a fresh reviewer should be able to tell which task's tests missed
the case.
