# Published Budget Chart Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The published page's Budget panel shows an inline-SVG donut plus one
line per category with its total (mirroring the app's own Budget tab), and
stops shipping individual expense descriptions — today they end up in the
`<table>` the panel renders **and** in the `window.TRIP` JSON payload
embedded in `index.html`, since the whole page must render with the API gone.

**Architecture:** Two independent fixes. (1) `StaticSiteRenderer` currently
maps every `BudgetItem` into a `PublishedTrip.Budget.Line` and inlines the
list as JSON — that has to stop at the source, not just stop being rendered,
because `index.html` embeds the JSON verbatim regardless of what `page.js`
draws from it. (2) `page.js`'s `budgetPanel()` gains a `donutChart()` helper
that recreates the app's `donutSegments` stroke-dasharray math using
`document.createElementNS` (plain DOM, not Alpine — the "`x-for` can't live
inside `<svg>`" trap in CLAUDE.md is an Alpine/template-parsing limitation
that does not apply to this vanilla-JS file), and its existing per-category
bar rows are kept (they already are "one line per category with its total")
while the line-item `<table>` is deleted outright.

**Tech Stack:** Spring Boot 3.5 (Java 21) for the snapshot; the published
page's own vanilla ES5 JS/CSS (`planner-api/src/main/resources/publish/`) —
deliberately dependency-free and separate from the frontend's Alpine/Sass
build, so publishing never depends on the frontend having been compiled.

**Spec:** `.claude/briefs/2026-09-12-published-budget-chart.md`

## Global Constraints

- The published page stays self-contained: no chart library, no external
  request. `StaticSiteRendererTest.inlinesItsOwnStylesheetAndScriptWithNoExternalReferences`
  already asserts this (no `src="http`, `href="http`, or `localhost`) and
  must keep passing unmodified — the donut is inline SVG built with
  `document.createElementNS`, adding no new reference of any kind.
  Untouched by this plan.
- The missing-exchange-rate warning keeps showing exactly as it does today —
  untouched (`budget.currenciesMissingRates`, unchanged in this plan).
- Category colours keep coming from `PublishStyle.color(ChecklistCategory)`,
  which already matches the Jekyll site's `budget_categories.yml`. This
  plan does not touch `PublishStyle` — the colour is already on
  `PublishedTrip.Budget.Category.color` today.
- Out of scope: the reference page's "By Country" and "Forecast" views. Not
  touched, not added.
- Out of scope: `TripViews.BudgetView` and the app's own Budget tab
  (`js/pages/trip/budget.js`, `trip.html`) — those already have their own
  donut and are the thing this plan's chart is modelled on, not modified by
  it. `BudgetService.Summary.items()` keeps returning every line for the
  live app; only the *published-page snapshot* stops carrying them.

## File Structure

| File | Responsibility |
|---|---|
| `planner-api/.../publish/api/PublishedTrip.java` | drop `Budget.items` / the `Line` record |
| `planner-api/.../publish/api/StaticSiteRenderer.java` | stop building `Line`s; drop the now-unused `rates` param on `toView(Summary, Map)` |
| `planner-api/.../publish/StaticSiteRendererTest.java` | new guardrail test: a manual expense's description never appears in the rendered page |
| `planner-api/src/main/resources/publish/page.js` | new `donutChart()`; `budgetPanel()` rewritten to drop the `<table>` and lay out donut + bars like the app does |
| `planner-api/src/main/resources/publish/page.css` | new `.budget-summary` / `.budget-donut` rules; drop the now-dead table rules |

---

### Task 1: Backend — stop shipping individual expense descriptions

**Files:**
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/publish/api/PublishedTrip.java`
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/publish/api/StaticSiteRenderer.java`
- Test: `planner-api/src/test/java/com/josephinealinea/planner/publish/StaticSiteRendererTest.java`

**Interfaces:**
- Consumes: nothing from elsewhere in this plan.
- Produces, for Task 2: the `window.TRIP` payload's `budget` object keeps
  `displayCurrency`, `total`, `byCategory` (unchanged shape: `key`, `label`,
  `icon`, `color`, `amount`) and `currenciesMissingRates`. It no longer has
  an `items` (or any other) key carrying per-expense data — `page.js` must
  not expect one.

- [ ] **Step 1: Remove the `Line` record and the `items` field**

In `PublishedTrip.java`, replace the `Budget` record (currently lines 60–75):

```java
    public record Budget(String displayCurrency,
                         BigDecimal total,
                         List<Category> byCategory,
                         List<Line> items,
                         List<String> currenciesMissingRates) {

        public record Category(String key, String label, String icon, String color, BigDecimal amount) {}

        public record Line(String description,
                           String categoryLabel,
                           String icon,
                           BigDecimal amount,
                           String currency,
                           BigDecimal converted,
                           String date) {}
    }
```

with:

```java
    public record Budget(String displayCurrency,
                         BigDecimal total,
                         List<Category> byCategory,
                         List<String> currenciesMissingRates) {

        public record Category(String key, String label, String icon, String color, BigDecimal amount) {}
    }
```

- [ ] **Step 2: Stop building lines in the renderer**

In `StaticSiteRenderer.java`, replace `toView(BudgetService.Summary, Map)`
(currently lines 205–235):

```java
    private PublishedTrip.Budget toView(BudgetService.Summary summary, Map<String, BigDecimal> rates) {
        List<PublishedTrip.Budget.Category> categories = new ArrayList<>();
        summary.byCategory().forEach((key, amount) -> {
            if (amount.signum() == 0) return;
            ChecklistCategory category = ChecklistCategory.valueOf(key);
            categories.add(new PublishedTrip.Budget.Category(
                    category.dataKey(),
                    category.label(),
                    PublishStyle.icon(category),
                    PublishStyle.color(category),
                    amount));
        });

        List<PublishedTrip.Budget.Line> lines = summary.items().stream()
                .map(item -> new PublishedTrip.Budget.Line(
                        item.getDescription(),
                        item.getCategory().label(),
                        PublishStyle.icon(item.getCategory()),
                        item.getAmount(),
                        item.getCurrency(),
                        BudgetService.convert(item, summary.displayCurrency(), rates),
                        iso(item.getDate())))
                .toList();

        return new PublishedTrip.Budget(
                summary.displayCurrency(),
                summary.total(),
                categories,
                lines,
                summary.currenciesMissingRates());
    }
```

with:

```java
    private PublishedTrip.Budget toView(BudgetService.Summary summary) {
        List<PublishedTrip.Budget.Category> categories = new ArrayList<>();
        summary.byCategory().forEach((key, amount) -> {
            if (amount.signum() == 0) return;
            ChecklistCategory category = ChecklistCategory.valueOf(key);
            categories.add(new PublishedTrip.Budget.Category(
                    category.dataKey(),
                    category.label(),
                    PublishStyle.icon(category),
                    PublishStyle.color(category),
                    amount));
        });

        return new PublishedTrip.Budget(
                summary.displayCurrency(),
                summary.total(),
                categories,
                summary.currenciesMissingRates());
    }
```

Update the one call site, in `snapshot()` (currently line 147):

```java
                toView(budget, trip.getExchangeRates()));
```

to:

```java
                toView(budget));
```

Remove the now-unused import (line 4 — nothing else in this file names
`BudgetItem`; its only other use was the inferred lambda parameter inside
the deleted `.map(item -> ...)` block):

```java
import com.josephinealinea.planner.budget.domain.BudgetItem;
```

- [ ] **Step 3: Verify it compiles**

```bash
cd planner-api && ./gradlew compileJava
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Write the failing guardrail test**

In `StaticSiteRendererTest.java`, add a second, unlinked budget item to the
existing `seed()` method (right after the existing `expense` is built and
saved, i.e. after the current last line of `seed()`,
`budget.save(SLUG, expense);`):

```java
        BudgetItem vaccine = new BudgetItem();
        vaccine.setId("budget-2");
        vaccine.setTripId("trip-1");
        vaccine.setCategory(ChecklistCategory.OTHERS);
        vaccine.setDescription("Yellow fever vaccine");
        vaccine.setAmount(new BigDecimal("45.00"));
        vaccine.setCurrency("USD");
        vaccine.setDate(LocalDate.of(2026, 10, 20));
        budget.save(SLUG, vaccine);
```

This is deliberately a manual expense (no `itineraryItemId`) with a
description that appears nowhere else on the page, unlike the existing
seeded expense whose description ("Delta flight...") legitimately also
appears via its linked itinerary entry — using that one for this assertion
would prove nothing.

Add a new test, alongside the other `@Test` methods:

```java
    @Test
    void neverLeaksAnIndividualExpenseDescription() throws Exception {
        String html = render("minima");

        // Per-category totals are what a published page should show; a
        // manual expense's own description must not reach the page at all —
        // not in the rendered markup, and not in the inlined window.TRIP
        // JSON either, since that is also part of index.html's text.
        assertThat(html).doesNotContain("Yellow fever vaccine");
    }
```

- [ ] **Step 5: Run it to confirm it passes**

```bash
cd planner-api && ./gradlew test --tests 'StaticSiteRendererTest'
```
Expected: `BUILD SUCCESSFUL`, all tests in the class passing (this test would
have failed before Steps 1–2, since the old code put the description
straight into the JSON payload).

- [ ] **Step 6: Run the full suite**

```bash
cd planner-api && ./gradlew test
```
Expected: `BUILD SUCCESSFUL`. Confirms nothing else constructs
`PublishedTrip.Budget` or `.Line` positionally (nothing else does — checked
during planning) and that removing the `rates` parameter didn't strand a
second call site.

- [ ] **Step 7: Commit**

Not run by you — the project's git guardrail blocks commits from an
assistant session. Leave the changes staged/unstaged for the user to commit.

---

### Task 2: Frontend (publish resources) — donut chart, drop the line-item table

**Files:**
- Modify: `planner-api/src/main/resources/publish/page.js`
- Modify: `planner-api/src/main/resources/publish/page.css`

**Interfaces:**
- Consumes: `window.TRIP.budget` per Task 1's new shape — `byCategory` (with
  `icon`, `label`, `color`, `amount` per entry), `total`, `displayCurrency`,
  `currenciesMissingRates`. No `items`/`lines` array exists any more; do not
  reference one.
- Produces: nothing later tasks depend on (this is the last task).

- [ ] **Step 1: Add the donut chart helper**

In `page.js`, add a new function immediately before `function budgetPanel() {`
(currently line 201):

```js
  /**
   * Same construction as the app's own Budget tab: a background ring plus
   * one stroke-dasharray arc per category on a shared circle (r=42, so
   * circumference = 2π·42), each rotated -90° so the first arc starts at
   * 12 o'clock. Built with document.createElementNS rather than a template
   * loop — this file has no templating engine to trip over SVG namespacing.
   */
  function donutChart(categories) {
    var SVG_NS = 'http://www.w3.org/2000/svg';

    function ring(stroke, dasharray, dashoffset) {
      var c = document.createElementNS(SVG_NS, 'circle');
      c.setAttribute('cx', '50');
      c.setAttribute('cy', '50');
      c.setAttribute('r', '42');
      c.setAttribute('fill', 'none');
      c.setAttribute('stroke-width', '14');
      c.setAttribute('stroke', stroke);
      if (dasharray) {
        c.setAttribute('transform', 'rotate(-90 50 50)');
        c.setAttribute('stroke-dasharray', dasharray);
        c.setAttribute('stroke-dashoffset', dashoffset);
      }
      return c;
    }

    var svg = document.createElementNS(SVG_NS, 'svg');
    svg.setAttribute('class', 'budget-donut');
    svg.setAttribute('viewBox', '0 0 100 100');
    svg.setAttribute('role', 'img');
    svg.setAttribute('aria-label', 'Spend by category');
    svg.appendChild(ring('var(--tp-surface-alt)', null, null));

    var total = categories.reduce(function (sum, c) { return sum + (Number(c.amount) || 0); }, 0);
    if (total > 0) {
      var circumference = 2 * Math.PI * 42;
      var offset = 0;
      categories.forEach(function (cat) {
        var amount = Number(cat.amount) || 0;
        var length = (amount / total) * circumference;
        svg.appendChild(ring(cat.color || 'var(--tp-accent)',
          length + ' ' + (circumference - length), -offset));
        offset += length;
      });
    }

    return svg;
  }

```

- [ ] **Step 2: Rewrite `budgetPanel()` — drop the table, lay out donut + bars**

Replace the whole existing `budgetPanel()` function (currently lines
201–277, immediately after the function you just inserted above):

```js
  function budgetPanel() {
    var budget = trip.budget || {};
    var categories = list(budget.byCategory);
    var lines = list(budget.items);
    var panel = section('budget', '💰 Budget');

    if (!lines.length) {
      panel.appendChild(el('p', 'empty', 'No costs recorded yet.'));
      return panel;
    }

    var summary = el('div', 'card');
    summary.appendChild(el('div', 'budget-total', money(budget.total, budget.displayCurrency)));
    summary.appendChild(el('div', 'budget-total-label', 'Total, in ' + (budget.displayCurrency || '')));

    var largest = categories.reduce(function (max, c) {
      return Math.max(max, Number(c.amount) || 0);
    }, 0);

    categories.forEach(function (category) {
      var amount = Number(category.amount) || 0;
      var row = el('div', 'bar-row');

      var head = el('div', 'bar-head');
      head.appendChild(el('span', null, (category.icon || '') + ' ' + category.label));
      head.appendChild(el('span', null, money(amount, budget.displayCurrency)));
      row.appendChild(head);

      var track = el('div', 'bar-track');
      var fill = el('div', 'bar-fill');
      fill.style.width = (largest > 0 ? (amount / largest) * 100 : 0) + '%';
      fill.style.background = category.color || 'var(--tp-accent)';
      track.appendChild(fill);
      row.appendChild(track);

      summary.appendChild(row);
    });

    if (list(budget.currenciesMissingRates).length) {
      summary.appendChild(el('div', 'warn',
        'Not included in the total — no exchange rate set for: '
        + budget.currenciesMissingRates.join(', ')));
    }

    panel.appendChild(summary);

    var wrap = el('div', 'table-wrap');
    var table = el('table');
    var thead = el('thead');
    var headRow = el('tr');
    ['', 'Description', 'Category', 'Amount', 'In ' + (budget.displayCurrency || ''), 'Date']
      .forEach(function (label, index) {
        var th = el('th', index === 3 || index === 4 ? 'num' : null, label);
        headRow.appendChild(th);
      });
    thead.appendChild(headRow);
    table.appendChild(thead);

    var tbody = el('tbody');
    lines.forEach(function (line) {
      var row = el('tr');
      row.appendChild(el('td', null, line.icon || ''));
      row.appendChild(el('td', 'wrap', line.description || ''));
      row.appendChild(el('td', null, line.categoryLabel || ''));
      row.appendChild(el('td', 'num', money(line.amount, line.currency)));
      row.appendChild(el('td', 'num', line.converted == null ? '—' : money(line.converted)));
      row.appendChild(el('td', null, line.date ? shortDate(line.date) : ''));
      tbody.appendChild(row);
    });
    table.appendChild(tbody);
    wrap.appendChild(table);

    var tableCard = el('div', 'card');
    tableCard.appendChild(wrap);
    panel.appendChild(tableCard);
    return panel;
  }
```

with:

```js
  function budgetPanel() {
    var budget = trip.budget || {};
    var categories = list(budget.byCategory);
    var panel = section('budget', '💰 Budget');

    if (!categories.length) {
      panel.appendChild(el('p', 'empty', 'No costs recorded yet.'));
      return panel;
    }

    var card = el('div', 'card');
    var layout = el('div', 'budget-summary');

    var left = el('div');
    left.appendChild(el('div', 'budget-total', money(budget.total, budget.displayCurrency)));
    left.appendChild(el('div', 'budget-total-label', 'Total, in ' + (budget.displayCurrency || '')));
    left.appendChild(donutChart(categories));
    layout.appendChild(left);

    var largest = categories.reduce(function (max, c) {
      return Math.max(max, Number(c.amount) || 0);
    }, 0);

    var bars = el('div');
    categories.forEach(function (category) {
      var amount = Number(category.amount) || 0;
      var row = el('div', 'bar-row');

      var head = el('div', 'bar-head');
      head.appendChild(el('span', null, (category.icon || '') + ' ' + category.label));
      head.appendChild(el('span', null, money(amount, budget.displayCurrency)));
      row.appendChild(head);

      var track = el('div', 'bar-track');
      var fill = el('div', 'bar-fill');
      fill.style.width = (largest > 0 ? (amount / largest) * 100 : 0) + '%';
      fill.style.background = category.color || 'var(--tp-accent)';
      track.appendChild(fill);
      row.appendChild(track);

      bars.appendChild(row);
    });
    layout.appendChild(bars);

    card.appendChild(layout);

    if (list(budget.currenciesMissingRates).length) {
      card.appendChild(el('div', 'warn',
        'Not included in the total — no exchange rate set for: '
        + budget.currenciesMissingRates.join(', ')));
    }

    panel.appendChild(card);
    return panel;
  }
```

Note the empty-state check changes from `!lines.length` to
`!categories.length` — `categories` is already filtered server-side to
non-zero amounts (`if (amount.signum() == 0) return;` in `toView`), and
every budget item contributes a positive amount to exactly one category, so
"no categories" and "no costs recorded" are the same condition. `lines` no
longer exists on the payload at all after Task 1.

- [ ] **Step 3: Confirm no reference to the removed data survives**

```bash
cd planner-api
grep -n "budget.items\|\.items)" src/main/resources/publish/page.js
```
Expected: no output.

- [ ] **Step 4: Add the donut/layout CSS, remove the now-dead table CSS**

In `page.css`, insert after the `.budget-total-label` rule (currently line
251) and before `.bar-row` (currently line 253):

```css
.budget-summary {
  display: grid;
  gap: 24px;
  grid-template-columns: minmax(180px, 220px) 1fr;
  align-items: start;
}

.budget-donut {
  display: block;
  width: 100%;
  max-width: 170px;
  margin: 12px auto 0;
}

```

Delete the now-dead table rules (currently lines 282–289 — nothing in
`page.js` creates a `<table>`, `<th>`, or `<td>` any more after Step 2, so
these selectors match nothing):

```css
/* Wide content scrolls inside its own container, never the page. */
.table-wrap { overflow-x: auto; }

table { width: 100%; border-collapse: collapse; font-size: 0.9rem; }
th, td { padding: 8px 10px; text-align: left; border-bottom: 1px solid var(--tp-border); white-space: nowrap; }
th { color: var(--tp-muted); font-weight: 600; font-size: 0.8rem; text-transform: uppercase; letter-spacing: 0.04em; }
td.num, th.num { text-align: right; }
td.wrap { white-space: normal; min-width: 200px; }
```

Add the mobile collapse to the existing `@media (max-width: 600px)` block
at the end of the file, rather than opening a second one:

```css
@media (max-width: 600px) {
  .page { padding: 20px 14px 56px; }
  .entry { flex-wrap: wrap; }
  .entry-cost { width: 100%; padding-left: 27px; }
  .budget-summary { grid-template-columns: 1fr; }
}
```

- [ ] **Step 5: Run the full suite once more**

```bash
cd planner-api && ./gradlew test
```
Expected: `BUILD SUCCESSFUL`. `page.js`/`page.css` are plain resource files
with no Java test executing them directly, so this mainly reconfirms Task
1's test and the pre-existing no-external-refs assertion are both still
green — the real verification of this task is the manual pass below.

- [ ] **Step 6: Manually verify — publish a trip and inspect the static output**

Start the API if it is not already running:
```bash
cd planner-api && BOOTSTRAP_OWNER_EMAIL=you@example.com BOOTSTRAP_OWNER_PASSWORD=password123 ./gradlew bootRun
```

As the trip's owner, publish a trip that has expenses in at least two
different categories (any existing trip with budget data works; add a
second-category expense first if it only has one). In the app, open that
trip's Publish tab and publish it (or call the endpoint directly):
```bash
curl -s -b /tmp/j -c /tmp/j -X POST -H "X-XSRF-TOKEN: $(grep XSRF-TOKEN /tmp/j | awk '{print $7}')" \
  http://localhost:8080/api/v1/trips/<tripId>/publish
```

1. `grep -c 'Yellow fever vaccine\|<table' planner-api/data/published/<slug>/index.html`
   — expected `0` for a real expense description of your own choosing (the
   Java test already covers this exactly; this repeats it against a live
   publish rather than the test fixture).
2. Open `planner-api/data/published/<slug>/index.html` directly in a
   browser (a `file://` URL, or any plain static server) **with the API
   stopped**. Expected: the page renders fully, including a donut chart in
   the Budget panel with one coloured arc per category and one bar-row per
   category beneath its total — no network requests fail because none are
   made.
3. Compare the donut's proportions and the total/per-category figures
   against the same trip's in-app Budget tab (`trip.html#budget`, signed in
   as a member). Expected: total matches, each category's amount matches,
   and the arc colours match the bar colours already shown on both pages.
4. Confirm the missing-exchange-rate warning still appears when applicable
   (a trip with an expense in a currency that has no configured rate).

- [ ] **Step 7: Commit**

Not run by you — leave staged for the user, same as Task 1.

---

## Verification against the brief

- [ ] The published page shows a chart and one line per category with its
      total → Task 2, Steps 1–2 (donut) and the retained bar-rows; Step 6
      browser-verifies both together.
- [ ] No individual expense description appears: `grep -c 'Yellow fever
      vaccine' data/published/<slug>/index.html` returns 0 → Task 1, Steps
      1–2 remove the data at the source (the JSON payload, not just the
      rendering) and Step 4's test asserts it; Task 2 Step 6 repeats the
      check against a real publish.
- [ ] The grand total and per-category figures match the app's Budget tab
      → both read the same `BudgetService.Summary`/`byCategory` data by
      construction; Task 2 Step 6.3 cross-checks it visually.
- [ ] `./gradlew test` passes, including the existing no-external-refs
      assertion → Task 1 Step 6, Task 2 Step 5.
- [ ] The page still renders correctly from a plain static server with the
      API stopped → Task 2 Step 6.2.
