# Published page: budget chart, no line items

**Status:** done
**Date:** 2026-09-12
**Source:** `.claude/prompt/20260912-changes.md` item 6
**Plan:** `.claude/plans/2026-09-12-published-budget-chart.md`

## Job
Replace the published page's budget line-item table with a chart plus per-category
totals only.

## Why
The published page has no chart at all — `publish/page.js` draws bars but no donut,
unlike the in-app Budget tab. Worse, it prints every expense row, so hotel names and
booking descriptions end up on a page whose whole point is being shared publicly.
Category totals are what a reader wants and all they should get.

## Guardrails
- Must not change: the page stays self-contained. No chart library, no external
  request — `StaticSiteRendererTest` asserts zero external refs and that must keep
  passing. Draw the chart as inline SVG, as the app's Budget tab already does.
- Must not change: the missing-exchange-rate warning still shows.
- Constraint: category colours stay the ones in `PublishStyle`, which match the
  Jekyll site's `budget_categories.yml`.
- Out of scope: the reference page's "By Country" and "Forecast" views.

## Done means
- [x] The published page shows a chart and one line per category with its total.
      `page.js`'s `budgetPanel()` renders a `donutChart()` (inline SVG, same
      stroke-dasharray construction as the app's own Budget tab) beside the
      existing per-category bar rows. Verified on a real published trip with
      4 categories of spend (157.48/150/80/22.50 GBP): arc lengths and
      cumulative offsets hand-checked against `(amount/total)*circumference`,
      exact match, ring closes with no gap or overlap.
- [x] No individual expense description appears. `PublishedTrip.Budget` no
      longer has an `items`/`Line` field at all — the data is gone before it
      ever reaches JSON, not just hidden from rendering. A new test,
      `StaticSiteRendererTest.neverLeaksAnIndividualExpenseDescription`, uses
      exactly this brief's own example ("Yellow fever vaccine" on a manual,
      unlinked expense) and asserts it out of the rendered page. Independently
      re-verified live: published a trip with a distinctly-named expense
      ("Malaria prophylaxis tablets") and confirmed 0 matches in the output
      `index.html`, including inside the embedded JSON payload.
- [x] The grand total and per-category figures match the app's Budget tab —
      both read the identical `BudgetService.Summary` by construction; cross-
      checked live on the same trip (409.98 GBP total, same 4 category
      amounts on both the published page and `trip.html`'s Budget tab).
- [x] `./gradlew test` passes, including the existing no-external-refs
      assertion — 38 tests, 0 failures, force-reran (`--rerun-tasks`) rather
      than trusting a cached result.
- [x] The page still renders correctly from a plain static server with the
      API stopped. Verified with the API process fully killed and the
      published directory served by a plain `python3 -m http.server`
      instead: page rendered identically, exactly one network request total
      (the HTML document itself), zero console errors.
