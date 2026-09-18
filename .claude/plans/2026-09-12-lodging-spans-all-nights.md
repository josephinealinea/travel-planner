# Lodging Spans All Nights Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A lodging plan appears on every day of the stay — check-in 24 Dec to
check-out 27 Dec shows up on 24, 25, 26 and 27 — in the app's Itinerary tab and
on the published day-by-day page, while still being one booking with one budget
row.

**Architecture:** The span is **derived at display time, not stored**. A lodging
plan already records the whole stay: `PlanTemplates` fills `startAt` with
check-in 15:00 and `endAt` with check-out 11:00, so the dates needed are on the
single `ItineraryItem` today — nothing new has to be written, migrated, or kept
in step. The change is confined to the two functions that group items into days:
`StaticSiteRenderer.days()` in Java and the `itineraryDays` getter in JS, each of
which emits a lodging item once per day from check-in to check-out instead of
once on check-in.

The alternative — creating one `ItineraryItem` per night — was rejected. It puts
the brief's main hazard (four entries becoming four budget rows and quadrupling
the total) permanently into the data model, and turns editing, deleting, bulk
selecting and renaming one stay into four-row operations. Deriving instead means
`BudgetSync` is not touched at all, so one stay keeps producing exactly one
budget row **by construction rather than by care**, and existing lodging plans
start spanning with no migration. It also follows the rule the codebase already
states: derived values are never stored, and the same derivation is written
twice — once in Java, once in JS — exactly as nights already are.

**Tech Stack:** Spring Boot 3.5 (Java 21) for the published snapshot; Alpine.js
in `planner-web/js/pages/trip/` for the app; the published page's own
dependency-free ES5 in `planner-api/src/main/resources/publish/`.

**Spec:** `.claude/briefs/2026-09-12-lodging-spans-all-nights.md`

## Global Constraints

- **One budget row per stay.** `BudgetSync` must not be edited by this work. The
  trip total is identical before and after a plan starts spanning.
- **Only lodging spans.** `TRANSPORTATION`, `ACTIVITIES` and `OTHERS` produce
  exactly one entry, on their start day, with the times they show today.
- **A stay with no `endAt`, or an `endAt` on or before `startAt`, is one entry.**
  Never fewer than one, never a backwards loop.
- The published page stays self-contained: no new external request.
- Out of scope: editing one night independently of the others, per-night
  pricing, and any change to how a stay is stored.

## Resolved: the brief's open question

The brief asks whether the check-out day gets an entry. **It does** — the source
request is explicit: *"check in is 24.12 check out at 27.12, should create
lodging itinerary for 24, 25, 26, and 27"*. Four entries for a stay `Nights`
calls 3N.

The two numbers disagreeing is real but not confusing in practice, because the
days do not read alike: the check-in day shows 15:00, the check-out day shows
11:00, and the nights between show no time at all. The count in
"Plan 3N accommodation" still counts nights, which is what it says.

## Per-day display rules

These apply on both sides and are what the tests assert.

| Day of the stay | Time shown | Cost shown |
|---|---|---|
| Check-in day | check-in time (15:00) | yes |
| Nights in between | none | no |
| Check-out day | check-out time (11:00) | no |
| Single-day item (all non-lodging) | unchanged: start, and end when same day | yes |

The cost rides on the check-in day alone. Repeating "50.00 EUR" on four days
would read as four charges and visually contradict the single budget row — the
same hazard as the budget guardrail, one layer up.

## File Structure

- `planner-api/.../publish/api/StaticSiteRenderer.java` — `days()` expands a stay
  across its dates; two small private helpers carry the rule.
- `planner-api/src/test/.../publish/StaticSiteRendererTest.java` — a multi-night
  stay lands on every day, non-lodging does not span, cost appears once.
- `planner-web/js/pages/trip/itinerary.js` — `itineraryDays` expands the same way.
- `planner-web/trip.html` — the entry loop reads the per-day view.

---

### Task 1: Published page — a stay appears on every day of the stay

**Files:**
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/publish/api/StaticSiteRenderer.java`
- Test: `planner-api/src/test/java/com/josephinealinea/planner/publish/StaticSiteRendererTest.java`

**Interfaces:**
- Consumes: `ItineraryItem` (`getStartAt`, `getEndAt`, `getCategory`, `getCost`,
  `getCurrency`), unchanged.
- Produces: `PublishedTrip.Day` / `PublishedTrip.Entry` — **record shapes
  unchanged**. A spanning stay is several `Entry` values that differ in their
  time and cost fields, so no schema change reaches `window.TRIP`.

- [ ] **Step 1: Replace `days()`**

In `StaticSiteRenderer.java`, replace:

```java
    private List<PublishedTrip.Day> days(List<ItineraryItem> items) {
        Map<LocalDate, List<PublishedTrip.Entry>> byDay = new LinkedHashMap<>();

        items.stream()
                .filter(item -> item.getStartAt() != null)
                .sorted(Comparator.comparing(ItineraryItem::getStartAt))
                .forEach(item -> byDay
                        .computeIfAbsent(item.getStartAt().toLocalDate(), key -> new ArrayList<>())
                        .add(new PublishedTrip.Entry(
                                item.getCategory().dataKey(),
                                item.getCategory().label(),
                                PublishStyle.icon(item.getCategory()),
                                item.getDescription(),
                                time(item.getStartAt() == null ? null : item.getStartAt().toLocalTime()),
                                time(item.getEndAt() == null ? null : item.getEndAt().toLocalTime()),
                                item.getCost() == null ? null : item.getCost().toPlainString(),
                                item.getCurrency())));

        return byDay.entrySet().stream()
                .map(entry -> new PublishedTrip.Day(iso(entry.getKey()), entry.getValue()))
                .toList();
    }
```

with:

```java
    private List<PublishedTrip.Day> days(List<ItineraryItem> items) {
        // Sorted, not insertion-ordered: a stay adds its later days while the
        // earlier plans on those days have not been reached yet, so insertion
        // order stops being chronological the moment anything spans.
        Map<LocalDate, List<PublishedTrip.Entry>> byDay = new TreeMap<>();

        items.stream()
                .filter(item -> item.getStartAt() != null)
                .sorted(Comparator.comparing(ItineraryItem::getStartAt))
                .forEach(item -> {
                    LocalDate first = item.getStartAt().toLocalDate();
                    LocalDate last = lastDayOf(item);
                    for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
                        byDay.computeIfAbsent(day, key -> new ArrayList<>())
                                .add(entryOn(item, day, first, last));
                    }
                });

        return byDay.entrySet().stream()
                .map(entry -> new PublishedTrip.Day(iso(entry.getKey()), entry.getValue()))
                .toList();
    }

    /**
     * The last day an entry appears on.
     *
     * Only a stay spans. You are in the room every day from check-in to
     * check-out, and a day-by-day page that lists the hotel on the arrival day
     * alone leaves the reader with nowhere to sleep on the nights between —
     * which is most of what the page is for. Everything else is an event on one
     * day, however long it runs.
     */
    private LocalDate lastDayOf(ItineraryItem item) {
        LocalDate first = item.getStartAt().toLocalDate();
        if (item.getCategory() != ChecklistCategory.LODGING || item.getEndAt() == null) {
            return first;
        }
        LocalDate checkout = item.getEndAt().toLocalDate();
        return checkout.isBefore(first) ? first : checkout;
    }

    /**
     * One day's view of an entry.
     *
     * A stay shows its check-in time on the first day and its check-out time on
     * the last, with nothing on the nights between — "15:00 – 11:00" repeated on
     * every day would be wrong on all but the ends. The cost rides on the first
     * day alone: it is one booking with one budget row, and repeating it would
     * read as four separate charges.
     *
     * An item that neither spans nor is lodging has first == last, so it keeps
     * exactly the times and cost it shows today.
     */
    private PublishedTrip.Entry entryOn(ItineraryItem item, LocalDate day,
                                        LocalDate first, LocalDate last) {
        boolean isFirst = day.equals(first);
        boolean isLast = day.equals(last);

        LocalTime shown = isFirst ? item.getStartAt().toLocalTime()
                : isLast && item.getEndAt() != null ? item.getEndAt().toLocalTime()
                : null;
        LocalTime until = isFirst && isLast && item.getEndAt() != null
                ? item.getEndAt().toLocalTime()
                : null;

        return new PublishedTrip.Entry(
                item.getCategory().dataKey(),
                item.getCategory().label(),
                PublishStyle.icon(item.getCategory()),
                item.getDescription(),
                time(shown),
                time(until),
                isFirst && item.getCost() != null ? item.getCost().toPlainString() : null,
                isFirst ? item.getCurrency() : null);
    }
```

Add the imports this needs, beside the existing ones:

```java
import java.time.LocalTime;
import java.util.TreeMap;
```

`ChecklistCategory` is already imported. Leave `LinkedHashMap` alone — other
methods in the file still use it.

- [ ] **Step 2: Verify it compiles**

```bash
cd planner-api && ./gradlew compileJava
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Seed a multi-night stay in the renderer test**

In `StaticSiteRendererTest.java`'s `seed(...)` method, straight after
`itinerary.save(SLUG, flight);`, add a stay in the Cusco window (25–31 Oct):

```java
        ItineraryItem stay = new ItineraryItem();
        stay.setId("plan-2");
        stay.setTripId("trip-1");
        stay.setChecklistItemId("check-1");
        stay.setCategory(ChecklistCategory.LODGING);
        stay.setDescription("Hotel in Cusco — check-in 25 Oct, check-out 28 Oct");
        stay.setStartAt(LocalDateTime.of(2026, 10, 25, 15, 0));
        stay.setEndAt(LocalDateTime.of(2026, 10, 28, 11, 0));
        stay.setCost(new BigDecimal("240.00"));
        stay.setCurrency("USD");
        itinerary.save(SLUG, stay);
```

Every type here is already imported in this file. Do not add a budget row for
it: `BudgetSync` is not in play in this test, and the point is the entry count,
not the rollup.

- [ ] **Step 4: Write the failing tests**

The renderer's `snapshot(Trip)` is private and the test lives in a different
package, so these read the `window.TRIP` payload the page carries rather than
widening production visibility for a test. The publish mapper emits nulls
rather than omitting them, so a missing time or cost is a JSON `null` and
`isNull()` is the right check.

Add these two helpers beside the existing private helpers:

```java
    /** The window.TRIP payload the rendered page carries, as JSON. */
    private JsonNode payload(String html) throws Exception {
        String marker = "window.TRIP = ";
        int start = html.indexOf(marker) + marker.length();
        // The payload escapes "</" as "<\/", so the first ";</script>" after it
        // is always the real terminator.
        int end = html.indexOf(";</script>", start);
        return new ObjectMapper().readTree(html.substring(start, end));
    }

    /** Every entry for one description, keyed by the day it appears on. */
    private Map<String, JsonNode> entriesByDay(JsonNode payload, String description) {
        Map<String, JsonNode> found = new LinkedHashMap<>();
        payload.get("days").forEach(day -> {
            for (JsonNode entry : day.get("entries")) {
                if (entry.get("description").asText().startsWith(description)) {
                    found.put(day.get("date").asText(), entry);
                }
            }
        });
        return found;
    }
```

and these tests alongside the other `@Test` methods:

```java
    @Test
    void aStayAppearsOnEveryDayFromCheckInToCheckOut() throws Exception {
        Map<String, JsonNode> stay = entriesByDay(payload(render("minima")), "Hotel in Cusco");

        // Four days for a three-night stay: you are still in the room on the
        // morning you check out.
        assertThat(stay.keySet())
                .containsExactly("2026-10-25", "2026-10-26", "2026-10-27", "2026-10-28");
    }

    @Test
    void aStayCarriesItsCostAndTimesOnlyWhereTheyApply() throws Exception {
        Map<String, JsonNode> stay = entriesByDay(payload(render("minima")), "Hotel in Cusco");

        // Check-in day: the arrival time, and the one and only cost.
        assertThat(stay.get("2026-10-25").get("startTime").asText()).isEqualTo("15:00");
        assertThat(stay.get("2026-10-25").get("cost").asText()).isEqualTo("240.00");

        // A night in between is just "you are here" — no time, no second charge.
        assertThat(stay.get("2026-10-26").get("startTime").isNull()).isTrue();
        assertThat(stay.get("2026-10-26").get("cost").isNull()).isTrue();

        // Check-out day: the time you have to be out by, still no charge.
        assertThat(stay.get("2026-10-28").get("startTime").asText()).isEqualTo("11:00");
        assertThat(stay.get("2026-10-28").get("cost").isNull()).isTrue();

        // One booking, one charge, however many days it shows on.
        long charged = stay.values().stream().filter(entry -> !entry.get("cost").isNull()).count();
        assertThat(charged).isEqualTo(1);
    }

    @Test
    void anEventStillAppearsOnItsOwnDayAlone() throws Exception {
        // The seeded flight leaves on the 24th at 22:15 and lands on the 25th at
        // 08:40, so it would span two days the moment the rule leaked past
        // lodging. It belongs to the day it departs.
        Map<String, JsonNode> flight = entriesByDay(payload(render("minima")), "Delta flight");

        assertThat(flight.keySet()).containsExactly("2026-10-24");
        assertThat(flight.get("2026-10-24").get("startTime").asText()).isEqualTo("22:15");
        assertThat(flight.get("2026-10-24").get("endTime").asText()).isEqualTo("08:40");
    }
```

Add the imports these need:

```java
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
```

`Map` is already imported.

- [ ] **Step 5: Run the class**

```bash
cd planner-api && ./gradlew test --tests 'StaticSiteRendererTest'
```
Expected: `BUILD SUCCESSFUL`, every test in the class passing.

- [ ] **Step 6: Run the full suite**

```bash
cd planner-api && ./gradlew test
```
Expected: `BUILD SUCCESSFUL`. Nothing else should move — no budget or sync test
may change behaviour, because no budget code was touched.

---

### Task 2: App Itinerary tab — the same span, same rules

**Files:**
- Modify: `planner-web/js/pages/trip/itinerary.js`
- Modify: `planner-web/trip.html`

**Interfaces:**
- Consumes: the trip bundle's `itinerary` array, unchanged.
- Produces: `itineraryDays` now yields `{ date, label, entries }` where each
  entry is a **per-day view** — `{ item, key, startAt, endAt, cost, currency }` —
  rather than the raw plan. The raw plan stays reachable as `entry.item`, which
  is what edit, select and delete must use.

- [ ] **Step 1: Add the day-view helpers**

In `itinerary.js`, above `export function itineraryTab() {`:

```js
/** "2026-12-24" -> "2026-12-25", in UTC so a timezone can never shift the day. */
function nextDay(iso) {
  const date = new Date(`${iso}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() + 1);
  return date.toISOString().slice(0, 10);
}

/**
 * The last day a plan appears on.
 *
 * Only a stay spans: you are in the room every day from check-in to check-out,
 * and an itinerary that lists the hotel on the arrival day alone shows nothing
 * at all for the nights in between. Everything else is an event on one day.
 */
function lastDayOf(item) {
  const first = item.startAt.slice(0, 10);
  if (item.category !== 'LODGING' || !item.endAt) return first;
  const checkout = item.endAt.slice(0, 10);
  return checkout < first ? first : checkout;
}

/**
 * One day's view of a plan.
 *
 * The check-in day shows its arrival time and the cost; the check-out day shows
 * the time you have to be out by; the nights between show neither. Repeating
 * the cost on every day would read as several charges against what is one
 * booking with one budget row.
 *
 * `item` is kept whole so editing, selecting and deleting still act on the
 * plan itself rather than on one day's view of it.
 */
function dayView(item, date, first, last) {
  const isFirst = date === first;
  const isLast = date === last;
  return {
    item,
    key: `${item.id}:${date}`,
    startAt: isFirst ? item.startAt : (isLast ? item.endAt : null),
    endAt: isFirst && isLast ? item.endAt : null,
    cost: isFirst ? item.cost : null,
    currency: item.currency,
  };
}
```

- [ ] **Step 2: Expand the days**

Replace the `itineraryDays` getter with:

```js
    /** [{ date, label, entries }], with undated entries collected at the end. */
    get itineraryDays() {
      const byDay = new Map();
      const undated = [];

      this.filteredItinerary.forEach((item) => {
        if (!item.startAt) {
          undated.push(dayView(item, null, null, null));
          return;
        }
        const first = item.startAt.slice(0, 10);
        const last = lastDayOf(item);
        for (let date = first; date <= last; date = nextDay(date)) {
          if (!byDay.has(date)) byDay.set(date, []);
          byDay.get(date).push(dayView(item, date, first, last));
        }
      });

      const days = [...byDay.entries()]
        .sort(([a], [b]) => a.localeCompare(b))
        .map(([date, entries]) => ({ date, label: longDate(date), entries }));

      if (undated.length) days.push({ date: null, label: 'No date yet', entries: undated });
      return days;
    },
```

`dayView(item, null, null, null)` makes an undated plan both the first day and
the last (`null === null`), so every ternary takes the `item` branch and the
view passes the plan's own fields through untouched — the row renders exactly as
it did before.

- [ ] **Step 3: Read the day view in the template**

In `trip.html`, in the itinerary entry loop, change the loop and the five places
that reach for the plan. Replace:

```html
              <template x-for="item in day.entries" :key="item.id">
                <div class="timeline-entry">
                  <label class="select-box select-box-tight">
                    <input type="checkbox" :checked="isItinSelected(item.id)"
                           @change="toggleItinSelected(item.id)"
                           :aria-label="`Select ${item.description}`">
                  </label>
                  <span class="timeline-time" x-text="entryTime(item)"></span>
```

with:

```html
              <template x-for="entry in day.entries" :key="entry.key">
                <div class="timeline-entry">
                  <label class="select-box select-box-tight">
                    <input type="checkbox" :checked="isItinSelected(entry.item.id)"
                           @change="toggleItinSelected(entry.item.id)"
                           :aria-label="`Select ${entry.item.description}`">
                  </label>
                  <span class="timeline-time" x-text="entryTime(entry)"></span>
```

and in the rest of that block replace `item.description` with
`entry.item.description`, `categoryOf(item.category)` with
`categoryOf(entry.item.category)`, `sourceChecklist(item)` with
`sourceChecklist(entry.item)`, `entryCost(item)` with `entryCost(entry)`,
`x-show="item.cost"` with `x-show="entry.cost"`, and
`openEditEntry(item)` with `openEditEntry(entry.item)`.

`entryTime` and `entryCost` are unchanged: they read `startAt`/`endAt`/`cost`/
`currency`, which the day view supplies scoped to that day. `:key="entry.key"`
because one plan now appears in several days' loops.

A stay ticked on one day shows ticked on all of them, and counts once — the
selection is of a plan, not of a day, and `itinSelected` still intersects with
`filteredItinerary`.

- [ ] **Step 4: Rebuild the stylesheets**

No CSS changes are needed, but bump the cache-busting version so a browser
picks up the edited HTML:

```bash
cd planner-web && npm run css
```

- [ ] **Step 5: Verify in the browser**

Start both servers if they are not up, then, signed in:

1. Add a destination with a date range of three or more nights.
2. Open its seeded "Plan …N accommodation" checklist item, press Plan, accept the
   pre-filled check-in/check-out, put a cost on it, save.
3. On the **Itinerary** tab: the stay appears on every day from check-in to
   check-out; the check-in day shows 15:00 and the cost, the check-out day shows
   11:00, the days between show neither.
4. On the **Budget** tab: exactly one row for that stay, and note the total.
5. Compare the total against what it was before the plan existed — it must be
   the stay's cost added once.
6. Tick the stay's checkbox on any day: "Delete selected (1)", not (4).
7. Press Edit from a middle day: the form opens with the real check-in and
   check-out, not blanks.
8. Publish the trip and open the public URL: the same four days each list the
   lodging, and the cost shows once.

- [ ] **Step 6: Run the suite once more**

```bash
cd planner-api && ./gradlew test
```
Expected: `BUILD SUCCESSFUL`.

---

## Verification against the brief

| Done means | Where it is proved |
|---|---|
| A lodging plan 24 → 27 Dec produces entries on 24, 25, 26 and 27 | Task 1 Step 4 (`aStayAppearsOnEveryDayFromCheckInToCheckOut`), Task 2 Step 5.3 |
| The trip budget total is identical before and after the plan spans | True by construction — `BudgetSync` is not touched and one stay is still one item; asserted in Task 1 Step 4 (one entry carries a cost) and checked in Task 2 Step 5.4–5.5 |
| `./gradlew test` passes, including a new test asserting the entry count | Task 1 Steps 4–6 |
| The published page shows the lodging on each of those four days | Task 1 Step 4, confirmed live in Task 2 Step 5.8 |

Guardrails: `BudgetSync` is not in any task's file list; non-lodging spanning is
asserted in `anEventStillAppearsOnItsOwnDayAlone`; `StaticSiteRenderer.days()` is
the published grouping the brief names, and it is Task 1's whole subject.
