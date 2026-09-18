# Paid By and Budget Pagination — Implementation Plan

> **For agentic workers:** Steps use checkbox (`- [ ]`) syntax. Each task is owned by one agent; read "Global Constraints" and "Parallelism" before your task.

**Goal:** Record one payer per expense and show it after Shared Amount; paginate the Budget tab's table with a per-browser page size set in Account → Appearance.

**Architecture:** API: a nullable `paidByUserId` on `BudgetItem`, validated by `TripMembers`, accepted by the budget endpoints and — as `costPaidByUserId` — by the itinerary endpoints, which hand it to `BudgetSync`. Frontend: a single-choice member picker in the three cost forms, a column in the budget table, and a page-size module modelled on `js/panel-mode.js`.

**Tech Stack:** Spring Boot 3.5 / Java 21 (YAML storage, JUnit 5), static HTML + Alpine.js, Sass.

**Spec:** `.claude/briefs/2026-09-17-paid-by-and-budget-pagination.md`

## Global Constraints

- **Git history is the user's.** A hook blocks commits, branches, merges, resets, stashes, checkouts and restores. No worktrees; skip anything that writes git state. Read-only `git diff`/`status`/`log` are fine.
- **Shared working tree, edited concurrently.** Use the **Edit tool only** for files another agent also touches (`trip.html`, `js/pages/trip/budget.js`) — no read-whole-file-and-rewrite scripts, which can silently erase another agent's edit made in between. If an Edit fails because the file changed, re-read and retry. Never revert or reformat what you did not write.
- Do not edit `CLAUDE.md`, do not bump `?v=`, do not start servers or browsers — all of that is the main session's integration step.
- `node_modules` exists; skip `npm ci`. `assets/css/` is gitignored build output.
- Colours only via tokens; comments explain *why*, matching the files you touch (see CLAUDE.md for the codebase's conventions and Traps).
- Paid by never changes any figure — totals, shares, forecast, country split are decided by `sharedByUserIds` alone.

## The API contract (Agents A and B both code to this — do not deviate)

| Where | Field | Meaning |
|---|---|---|
| `BudgetItem` JSON (read, in `GET /trips/{id}` → `budget.items[]`) | `paidByUserId` | member user id; **absent** when nobody (NON_NULL) |
| `POST /api/v1/trips/{tripId}/budget` body | `paidByUserId` | id → set; `null` / absent / `""` → no payer |
| `PATCH /api/v1/trips/{tripId}/budget/{itemId}` body | `paidByUserId` | absent/`null` → **leave unchanged**; `""` → **clear**; id → set |
| `POST /api/v1/trips/{tripId}/itinerary` body (and the checklist Plan form, which posts here) | `costPaidByUserId` | as POST budget, applied to the budget row the cost creates |
| `PATCH /api/v1/trips/{tripId}/itinerary/{itemId}` body | `costPaidByUserId` | as PATCH budget, applied to the plan's budget row |
| any of the above | an id that is not a trip member | **400** `ApiException.badRequest("That person is not a member of this trip.")` |

The frontend always sends the field on save: `paidByUserId: form.paidByUserId || ''` (or `costPaidByUserId`), so clearing it in the form really clears it.

## Parallelism

| Wave | Who | Tasks | Files owned |
|---|---|---|---|
| 1 | **Agent A** | Task 1 — API | `planner-api/**` only |
| 1 | **Agent B** | Task 2 — Paid by in the planner | `js/member-picker.js`, `js/pages/trip/checklist.js`, `js/pages/trip/itinerary.js`; in `js/pages/trip/budget.js` only the expense form + payer helpers; in `trip.html` the expense, Plan and itinerary forms and the budget table's **header row and row cells** |
| 1 | **Agent C** | Task 3 — pagination | `js/page-size.js` (new), `js/config.js`, `account.html`, `scss/components/_table.scss`; in `js/pages/trip/budget.js` only pagination state/getters and `toggleBudgetCategory`; in `trip.html` only the budget table's `x-for` attribute and a new pager after the table |
| 1 | **Main session** | Task 0 — the itinerary-edit freeze | browser only, no files unless a bug is found |
| 2 | **Main session** | Task 4 — integrate & verify | `?v=` bump, css build, full test run, browser checks, `CLAUDE.md`, brief |

Why this split is safe: A and B meet only at the contract above. B and C both touch `budget.js` and the budget table, but different methods and different attributes/lines, with Edit-tool-only discipline. Task 0 needs the signed-in browser, which no agent uses.

---

## Task 0: Explain the itinerary-edit freeze (main session, during wave 1)

- [ ] Close the automation tab group; open the trip on `#itinerary` in a fresh tab and bring it to the front with a screenshot before every scripted step (background tabs throttle timers to ~1/min and pause animation frames — see CLAUDE.md, Traps).
- [ ] Use only synchronous scripts or scripts whose awaits are short `requestAnimationFrame`/microtask waits; click Edit on "Roundtrip flight from Tallinn to LA" (has a cost) via the `computer` tool; screenshot; read form state synchronously.
- [ ] If it responds: record "automation artefact (hidden-tab timer throttling)" in the brief. If it freezes in a foreground tab: stop, follow superpowers:systematic-debugging, and report before Task 4.

---

## Task 1: Paid by in the API — Agent A

**Files:**
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/budget/domain/BudgetItem.java`
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/trips/api/TripMembers.java`
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/budget/api/BudgetService.java` (`Input`, `create`, `update`)
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/budget/web/BudgetController.java` (`CreateRequest`, `PatchRequest`)
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/itinerary/web/ItineraryController.java`
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/itinerary/api/ItineraryService.java`
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/itinerary/api/BudgetSync.java` (`afterSave`)
- Modify: every existing caller/test that constructs `BudgetService.Input`, `ItineraryService`'s input record, or calls `BudgetSync.afterSave` (compile will name them)
- Create: `planner-api/src/test/java/com/josephinealinea/planner/budget/BudgetPaidByTest.java`

**Interfaces:**
- Produces: `BudgetItem.getPaidByUserId()/setPaidByUserId(String)`; `TripMembers.validateOne(String) → String` (null for null/blank; throws 400 for a non-member); JSON per the contract table.

- [ ] **Step 1: Read first.** `BudgetSharingTest.java` (its `setUp` is the fixture to copy), `BudgetSyncTest.java`, and how `sharedByUserIds` flows through the files above — Paid by follows the same path with single-value semantics.

- [ ] **Step 2: Write the failing tests** in `BudgetPaidByTest`, reusing `BudgetSharingTest`'s fixture style (two members, temp dir YAML stores). One test each:
  - `anExpenseRecordsWhoPaidForIt` — create with member B's id → stored `paidByUserId == B`.
  - `anExpenseCreatedWithNoPayerHasNone` — create with `null` and with `""` → `null`.
  - `patchingWithoutAPayerLeavesItAlone` — create with B, patch with `null` → still B.
  - `patchingWithAnEmptyPayerClearsIt` — create with B, patch with `""` → `null`.
  - `aPayerWhoIsNotAMemberIsRejected` — expect `ApiException` (400).
  - `aPlansCostCarriesItsPayerOntoTheBudgetRowItCreates` — itinerary create with cost + `costPaidByUserId` B → the synced `BudgetItem` has B.
  - `repricingAPlanWithoutSendingAPayerLeavesItAsItWas` — then update cost with `costPaidByUserId` null → row still B.
  - `thePayerChangesNoFigure` — the same expense summarised for member A with payer A vs payer B gives identical `charged.total` and `shares`.
  - `thePayerSurvivesAYamlRoundTrip` — write, re-read through the repository, still B; and a hand-written YAML row with no `paidByUserId` reads as `null`.

- [ ] **Step 3: Run them to see them fail**

Run: `cd planner-api && ./gradlew test --tests 'BudgetPaidByTest'`
Expected: compilation failure / FAIL.

- [ ] **Step 4: Implement**
  - `BudgetItem`: `private String paidByUserId;` + plain getter/setter, placed beside `sharedByUserIds` with a comment: who put the money down; one member or nobody; never counted in any figure. No derived accessors (CLAUDE.md → Jackson traps).
  - `TripMembers.validateOne(String raw)`: `null`/blank → `null`; trimmed id not in `userIds` → `ApiException.badRequest("That person is not a member of this trip.")`; else the id.
  - `BudgetService.Input`: add `String paidByUserId` after `sharedByUserIds`. `create`: `item.setPaidByUserId(TripMembers.of(trip).validateOne(input.paidByUserId()))`. `update`: `if (input.paidByUserId() != null) item.setPaidByUserId(TripMembers.of(trip).validateOne(input.paidByUserId()));` (so `""` clears via `validateOne` → null).
  - `BudgetController.CreateRequest` / `PatchRequest`: add `String paidByUserId`, passed through.
  - Itinerary controller request records + `ItineraryService` input record: add `String costPaidByUserId` next to `costSharedByUserIds`; validate with `validateOne` **only when non-null** (null means "not sent"); pass to `budgetSync.afterSave`.
  - `BudgetSync.afterSave(..., Boolean charged, List<String> sharedByUserIds, String paidByUserId)` receives three possible values, and ItineraryService must keep them distinct: `null` when the request field was absent (leave alone), `""` when it was blank (clear), or the id after `validateOne` (set). In `afterSave`: on a created row, `if (paidByUserId != null && !paidByUserId.isBlank()) created.setPaidByUserId(paidByUserId)`; on an existing row, `if (paidByUserId != null) existing.setPaidByUserId(paidByUserId.isBlank() ? null : paidByUserId)`.
  - Update the class comment of `BudgetSync` where it lists what a later change updates (it names `status` and `sharedByUserIds`) to include `paidByUserId`.

- [ ] **Step 5: Run the new tests, then the whole suite**

Run: `cd planner-api && ./gradlew test --tests 'BudgetPaidByTest'` → PASS
Run: `cd planner-api && ./gradlew test` → BUILD SUCCESSFUL (report the test count).

- [ ] **Step 6: Confirm nothing publishes it**

Run: `grep -rn "paidBy" planner-api/src/main/java/com/josephinealinea/planner/publish planner-api/src/main/resources/publish`
Expected: no output.

---

## Task 2: Paid by in the planner — Agent B

**Files:**
- Modify: `planner-web/js/member-picker.js`
- Modify: `planner-web/js/pages/trip/budget.js` — expense form state, `openAddExpense`, `openEditExpense`, `saveExpense` payload, new helpers `paidByOfPlan`, `paidByLabel`, `choosePayer`
- Modify: `planner-web/js/pages/trip/checklist.js` — `blankPlan`, `openPlanForm`, `editPlan`, `savePlan` payload
- Modify: `planner-web/js/pages/trip/itinerary.js` — entry form init, `openAddEntry`, `openEditEntry`, `saveEntry` payload
- Modify: `planner-web/trip.html` — expense form, Plan form (in the drawer), itinerary entry form, budget table header + row cells

**Interfaces:**
- Consumes: the contract table; the component's existing `currentUserId`, `members`, `tripSharers`, `budgetRowOfPlan(plan)`.
- Produces: `choosePayer(form, userId)`, `paidByOfPlan(plan)`, `paidByLabel(item)`; `form.paidByUserId` on all three forms.

- [ ] **Step 1: `member-picker.js`** — add, with the file's comment style, and extend the header comment with the single-choice markup block:

```js
/**
 * Picks the one member who paid, or clears it when they are picked again.
 * Single choice rather than a list: one card goes down per expense. Nobody
 * is a valid answer — every row written before Paid by existed has nobody.
 */
export function choosePayer(form, userId) {
  form.paidByUserId = form.paidByUserId === userId ? '' : userId;
}
```

Markup block to document (and use in all three forms):

```html
<div class="field">
  <label id="my-paid-by-label">Paid by</label>
  <div class="chip-group" role="group" aria-labelledby="my-paid-by-label">
    <template x-for="person in tripSharers" :key="person.userId">
      <button type="button" class="chip"
              :aria-pressed="form.paidByUserId === person.userId"
              @click="choosePayer(form, person.userId)"
              x-text="person.label"></button>
    </template>
  </div>
</div>
```

- [ ] **Step 2: `budget.js`** — expose `choosePayer` on the tab object (as `toggleSharer`/`toggleLocation` are). Add:

```js
    /**
     * Who paid for the budget row a plan's cost created. A plan with no row yet
     * (no cost so far) starts with the member filling in the form, the same
     * default a brand-new cost gets.
     */
    paidByOfPlan(plan) {
      const row = this.budgetRowOfPlan(plan);
      return row ? (row.paidByUserId || '') : (this.currentUserId || '');
    },

    /**
     * The Paid by cell. Only current members' names are known here, so a payer
     * who has since left reads "Former member" — the id stays on the row.
     */
    paidByLabel(item) {
      if (!item.paidByUserId) return '—';
      const member = this.members.find((m) => m.userId === item.paidByUserId);
      return member ? (member.displayName || member.email) : 'Former member';
    },
```

Expense form: `paidByUserId: this.currentUserId || ''` in `openAddExpense`; `paidByUserId: item.paidByUserId || ''` in `openEditExpense`; `paidByUserId: this.expenseForm.paidByUserId || ''` in the `saveExpense` payload (both create and patch).

- [ ] **Step 3: `checklist.js`** — `blankPlan()` gains `paidByUserId: ''`; `openPlanForm` sets `paidByUserId: this.currentUserId || ''` on the new plan form; `editPlan` sets `paidByUserId: this.paidByOfPlan(plan)`; `savePlan` payload adds `costPaidByUserId: this.planForm.paidByUserId || ''` beside `costSharedByUserIds`.

- [ ] **Step 4: `itinerary.js`** — same three changes for `entryForm`: `openAddEntry` → current user; `openEditEntry` → `this.paidByOfPlan(item)`; `saveEntry` payload → `costPaidByUserId: this.entryForm.paidByUserId || ''`.

- [ ] **Step 5: `trip.html` forms**
  - **Expense form:** insert the Paid by field directly after the "Shared by" field (ids `expense-paid-by-label`, bound to `expenseForm`).
  - **Plan form:** after its "Shared by" field, `x-show="planForm.cost"`, ids `plan-paid-by-label`.
  - **Itinerary form:** make its cost block match the Plan form: add to its existing "Shared by" field the Plan form's hint verbatim (`Splits the cost between them. Pick nobody and it belongs to everyone on the trip.`), then the Paid by field with `x-show="entryForm.cost"`, ids `entry-paid-by-label`.

- [ ] **Step 6: `trip.html` budget table**
  - Header: `<th>Paid by</th>` immediately after `<th class="num">Shared Amount</th>`.
  - Row: immediately after the Shared Amount `<td … data-label="Your share" …>`:

```html
                    <td data-label="Paid by" x-text="paidByLabel(item)"></td>
```

Touch nothing else in the table — Agent C is changing its `x-for` and adding a pager below it.

- [ ] **Step 7: Static checks**

Run: `cd planner-web && node --check js/member-picker.js && node --check js/pages/trip/budget.js && node --check js/pages/trip/checklist.js && node --check js/pages/trip/itinerary.js`
Expected: no output.
Run: `cd planner-web && grep -c 'Paid by' trip.html && grep -c 'costPaidByUserId' js/pages/trip/checklist.js js/pages/trip/itinerary.js && grep -c 'data-label=' trip.html`
Expected: `Paid by` ≥ 5 (3 labels, th, td); each JS file 1; data-label `6`.

---

## Task 3: Budget pagination and the page-size setting — Agent C

**Files:**
- Create: `planner-web/js/page-size.js`
- Modify: `planner-web/js/config.js`
- Modify: `planner-web/account.html` (Appearance card markup + its module script)
- Modify: `planner-web/js/pages/trip/budget.js` — pagination state, getters, `toggleBudgetCategory`
- Modify: `planner-web/trip.html` — budget table `x-for` attribute; pager after the table
- Modify: `planner-web/scss/components/_table.scss` — pager styles

**Interfaces:**
- Produces: `BUDGET_PAGE_SIZES`, `savedBudgetPageSize()`, `saveBudgetPageSize(n)`; component `budgetPage`, `budgetPageSize`, `budgetPageCount`, `budgetCurrentPage`, `pagedBudgetItems`, `budgetPageFrom`, `budgetPageTo`, `goToBudgetPage(n)`.

- [ ] **Step 1: `config.js`** — append, following the file's `globalOverride` convention:

```js
/** Budget table rows per page until a member picks their own in Account → Appearance. */
export const DEFAULT_BUDGET_PAGE_SIZE =
  globalOverride('PLANNER_BUDGET_PAGE_SIZE', [10, 20, 50, 100], 20);
```

- [ ] **Step 2: `js/page-size.js`**, modelled on `panel-mode.js`:

```js
/**
 * How many budget rows the Budget tab shows per page.
 *
 * A per-browser preference like the theme and the panel style: it is about the
 * screen in front of you, not about the account, so it lives in localStorage
 * and is read once when the trip page loads.
 */
import { DEFAULT_BUDGET_PAGE_SIZE } from './config.js';

export const BUDGET_PAGE_SIZES = [10, 20, 50, 100];

const STORAGE_KEY = 'budgetPageSize';

export function savedBudgetPageSize() {
  try {
    const saved = Number(localStorage.getItem(STORAGE_KEY));
    return BUDGET_PAGE_SIZES.includes(saved) ? saved : DEFAULT_BUDGET_PAGE_SIZE;
  } catch {
    // Private browsing can throw on access, not just return null.
    return DEFAULT_BUDGET_PAGE_SIZE;
  }
}

export function saveBudgetPageSize(size) {
  const next = BUDGET_PAGE_SIZES.includes(Number(size)) ? Number(size) : DEFAULT_BUDGET_PAGE_SIZE;
  try { localStorage.setItem(STORAGE_KEY, String(next)); } catch { /* private mode */ }
  return next;
}
```

- [ ] **Step 3: `account.html`** — in the Appearance card, directly after the Theme `.field` and before "Published page":

```html
      <div class="field">
        <label for="budget-page-size">Budget rows per page</label>
        <select id="budget-page-size" class="control-narrow"></select>
        <p class="field-hint">Remembered in this browser. Applies the next time a trip opens.</p>
      </div>
```

In the page's module script, import `BUDGET_PAGE_SIZES, savedBudgetPageSize, saveBudgetPageSize` and wire it:

```js
  const pageSizeSelect = document.querySelector('#budget-page-size');
  pageSizeSelect.innerHTML = BUDGET_PAGE_SIZES
    .map((size) => `<option value="${size}">${size}</option>`).join('');
  pageSizeSelect.value = String(savedBudgetPageSize());
  pageSizeSelect.addEventListener('change', () => {
    pageSizeSelect.value = String(saveBudgetPageSize(pageSizeSelect.value));
  });
```

Place it beside the existing theme-radio wiring; match its style.

- [ ] **Step 4: `budget.js`** — import `savedBudgetPageSize` from `../../page-size.js`. State beside the other budget state: `budgetPage: 1,` and `budgetPageSize: savedBudgetPageSize(),`. Getters (after `filteredBudgetItems`):

```js
    /**
     * Paging is presentation only: the chart, totals and bulk selection all
     * read filteredBudgetItems, so every figure still covers every page.
     */
    get budgetPageCount() {
      return Math.max(1, Math.ceil(this.filteredBudgetItems.length / this.budgetPageSize));
    },

    /**
     * budgetPage clamped to what exists, so deleting the last rows of the last
     * page, or narrowing a filter, never leaves the table past its own end.
     */
    get budgetCurrentPage() {
      return Math.min(Math.max(1, this.budgetPage), this.budgetPageCount);
    },

    get pagedBudgetItems() {
      const start = (this.budgetCurrentPage - 1) * this.budgetPageSize;
      return this.filteredBudgetItems.slice(start, start + this.budgetPageSize);
    },

    get budgetPageFrom() {
      return this.filteredBudgetItems.length
        ? (this.budgetCurrentPage - 1) * this.budgetPageSize + 1 : 0;
    },

    get budgetPageTo() {
      return Math.min(this.budgetCurrentPage * this.budgetPageSize, this.filteredBudgetItems.length);
    },

    goToBudgetPage(page) {
      this.budgetPage = Math.min(Math.max(1, page), this.budgetPageCount);
    },
```

In `toggleBudgetCategory`, add `this.budgetPage = 1;` — a new filter starts from its first row.

These are getters on a tab object: they reach the component through `mergeTabs` (descriptors, not spread — CLAUDE.md → Traps). Do not touch the expense form or the payer helpers (Agent B).

- [ ] **Step 5: `trip.html`** — in the budget table body change only `x-for="item in filteredBudgetItems"` to `x-for="item in pagedBudgetItems"`. After the `</div>` that closes `<div class="table-wrap" x-show="filteredBudgetItems.length">`, add:

```html
          <!-- Shown only when there is more than one page. The figures above
               the table cover every page; only the rows are paged. -->
          <nav class="pager" x-show="budgetPageCount > 1" aria-label="Budget table pages">
            <span class="pager-status"
                  x-text="`Showing ${budgetPageFrom}–${budgetPageTo} of ${filteredBudgetItems.length}`"></span>
            <span class="pager-controls">
              <button type="button" class="btn btn-sm" :disabled="budgetCurrentPage <= 1"
                      @click="goToBudgetPage(budgetCurrentPage - 1)">← Previous</button>
              <span class="pager-page" aria-live="polite"
                    x-text="`Page ${budgetCurrentPage} of ${budgetPageCount}`"></span>
              <button type="button" class="btn btn-sm" :disabled="budgetCurrentPage >= budgetPageCount"
                      @click="goToBudgetPage(budgetCurrentPage + 1)">Next →</button>
            </span>
          </nav>
```

Touch nothing else in the table — Agent B is adding the Paid by header and cell.

- [ ] **Step 6: `_table.scss`** — append:

```scss
// Pager under a paginated table. Wraps on a phone, status line first.
.pager {
  display: flex;
  flex-wrap: wrap;
  gap: t.$space-2 t.$space-3;
  align-items: center;
  justify-content: space-between;
  margin-top: t.$space-3;
  font-size: 0.85rem;
  color: var(--tp-muted);
}

.pager-controls { display: flex; gap: t.$space-2; align-items: center; }
.pager-page { font-variant-numeric: tabular-nums; }
```

- [ ] **Step 7: Static checks**

Run: `cd planner-web && node --check js/page-size.js && node --check js/config.js && node --check js/pages/trip/budget.js && npm run css`
Expected: no output from `node --check`; css exits 0.
Run: `cd planner-web && grep -c 'pagedBudgetItems' trip.html && grep -c 'budget-page-size' account.html`
Expected: `1` and ≥ `2`.

---

## Task 4: Integrate and verify (main session, after A, B, C report)

- [ ] Grep that every agent's markers survived in `trip.html`/`budget.js` (`Paid by`, `paidByLabel`, `pagedBudgetItems`, `class="pager"`, `choosePayer`), and read the combined diff.
- [ ] `cd planner-api && ./gradlew test` → BUILD SUCCESSFUL. Restart the API so the running server knows `paidByUserId` (ask the user if it is their own process).
- [ ] `cd planner-web && npm run css`; bump `?v=70` → `?v=71` in every page; close the tab so the ES module graph reloads (CLAUDE.md → Traps).
- [ ] Browser, foreground tab, signed in, LATAM trip — **no data left changed**:
  - Add expense form: Paid by shows me pre-selected; clicking me clears it; Cancel.
  - Plan form and itinerary form: Paid by appears once a cost is typed; Cancel.
  - Budget table: Paid by column after Shared Amount; existing rows "—".
  - Account → Appearance: "Budget rows per page" below Theme, 20 selected; set 10, reload trip → 5 rows stay on one page (pager hidden); confirm pager logic by setting the page size to 10 in `localStorage` and temporarily `budgetPageSize = 2` via `Alpine.$data` → "Showing 1–2 of 5", Previous disabled, Next → "3–4", Category filter → page 1. Restore the setting to 20.
  - One end-to-end write, only with the user's go-ahead: edit one expense's Paid by, save, confirm the YAML row gains `paidByUserId`, then set it back.
- [ ] `CLAUDE.md`: a bullet under "The cascade" beside the Shared by rules — Paid by is one member or nobody, defaults to the form's author, never counted in any figure, PATCH `""` clears / absent leaves alone, not published, "Former member" fallback; and one line under Frontend architecture for `js/page-size.js`.
- [ ] Brief: tick verified boxes, record Task 0's finding, set status.
- [ ] Give the user the commit commands.
