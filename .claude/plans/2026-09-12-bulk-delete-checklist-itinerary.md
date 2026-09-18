# Bulk Delete on Checklist and Itinerary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Select several checklist or itinerary rows and delete them in one confirmed action, instead of one dialog per row.

**Architecture:** No backend change. Bulk delete loops the existing single-delete endpoints, which is what keeps every server-side cascade intact — see the ruling below. Shared pure helpers live in one small module; each tab keeps its own selection array, because both tabs merge into a single Alpine component and property names must not collide.

**Tech Stack:** Vanilla ES modules, Alpine.js (vendored), Dart Sass. No new dependencies.

**Spec:** `.claude/briefs/2026-09-12-bulk-delete-checklist-itinerary.md`

## Global Constraints

- The warning that a deleted checklist item's plans stay on the itinerary must appear for bulk deletes too, not only single ones.
- Deleting an itinerary row that carries a cost must still delete its budget row via `BudgetSync.afterDelete`. Bulk must not bypass that path.
- The Checklist drawer keeps its own Delete button. Only Itinerary loses its per-row one.
- Out of scope: bulk edit, bulk status change, and bulk delete on Destinations, Budget or Members.
- No frontend test runner exists in this project and adding one is out of scope (CLAUDE.md). Verification is browser-based with exact expected values.
- `.claude/` is this project's config root; do not write plans or notes anywhere else.

## Rulings made while planning

**Ruling: no backend bulk endpoint — the client loops the existing single-delete calls.**
Why: the guardrail says bulk must not bypass `BudgetSync.afterDelete`. Looping `DELETE /itinerary/{id}` *cannot* bypass it, because that endpoint is the path. `ChecklistService.delete` likewise unlinks affected plans (`plan.setChecklistItemId(null)`) on every call. A new bulk endpoint would mean reimplementing both cascades and re-earning trust in them. Trips hold tens of items, not thousands, so N sequential requests is acceptable.
Cost if wrong: deletes are not atomic — a mid-run failure leaves some rows deleted and some not. This is handled by reporting the real counts rather than claiming success (Task 1), not by hiding it. If atomicity is later wanted, that is a backend brief.

**Ruling: no "select all" control.**
Why: the brief asks for a checkbox per row and one delete action; YAGNI. Cost if wrong: a 20-item tidy-up still needs 20 clicks to select. Cheap to add later if it annoys.

**Ruling: stale ids are handled by intersection, not by hooking `reload()`.**
Why: every count and every delete derives from `selected ∩ currently-present`, so an id that vanishes (another member deleted it, a filter changed) simply stops counting. No cleanup hook to forget.
Cost if wrong: none identified — the intersection is computed at point of use.

## File Structure

| File | Responsibility |
|---|---|
| `planner-web/js/selection.js` | **new** — pure selection helpers and the sequential bulk-delete runner. No DOM, no Alpine. |
| `planner-web/js/pages/trip/checklist.js` | adds selection state + bulk delete for the checklist tab |
| `planner-web/js/pages/trip/itinerary.js` | adds selection state + bulk delete; drops per-row delete state |
| `planner-web/trip.html` | checkboxes, two toolbar actions, one confirm modal per tab; removes Itinerary's per-row Delete |
| `planner-web/scss/components/_checklist-item.scss` | wrapper row so a checkbox can sit beside the row button |
| `planner-web/scss/components/_timeline.scss` | checkbox cell in a timeline row |

---

### Task 1: Shared selection helpers

**Files:**
- Create: `planner-web/js/selection.js`

**Interfaces:**
- Consumes: nothing.
- Produces, for Tasks 2 and 3:
  - `toggleId(list: string[], id: string): void` — mutates in place, so Alpine reactivity holds
  - `selectedPresent(ids: string[], items: {id: string}[]): {id: string}[]` — the intersection, in list order
  - `runBulkDelete(items: {id:string}[], deleteOne: (id:string) => Promise<unknown>): Promise<{deleted: number, failed: number}>`

- [ ] **Step 1: Write the module**

Create `planner-web/js/selection.js`:

```js
/**
 * Row selection for bulk actions.
 *
 * Kept as free functions over a plain array rather than a class or a Set:
 * both trip tabs merge into one Alpine component, so each tab owns its own
 * array under its own name, and Alpine tracks plain arrays reliably where it
 * does not track Sets.
 */

/** Adds or removes an id, mutating in place so Alpine sees the change. */
export function toggleId(list, id) {
  const at = list.indexOf(id);
  if (at >= 0) list.splice(at, 1);
  else list.push(id);
}

/**
 * The selected items that still exist, in the order the list shows them.
 *
 * Everything derives from this intersection, so an id that disappears —
 * another member deleted it, a filter hid it, a reload dropped it — simply
 * stops counting. There is no stale-selection cleanup to forget.
 */
export function selectedPresent(ids, items) {
  return items.filter((item) => ids.includes(item.id));
}

/**
 * Deletes items one at a time through the ordinary single-delete endpoint.
 *
 * Looping the real endpoint is deliberate: it is what keeps the server-side
 * cascades — a plan's budget row, a checklist item's plan links — on exactly
 * the path they already take for a single delete.
 *
 * Sequential, not Promise.all: the YAML store rewrites one file per trip under
 * a lock, so parallel deletes would queue anyway while making a partial
 * failure harder to report accurately.
 */
export async function runBulkDelete(items, deleteOne) {
  let deleted = 0;
  let failed = 0;

  for (const item of items) {
    try {
      await deleteOne(item.id);
      deleted += 1;
    } catch {
      // Keep going: one bad id should not strand the rest of the selection.
      failed += 1;
    }
  }

  return { deleted, failed };
}
```

- [ ] **Step 2: Verify the helpers behave**

The project has no test runner, so verify in the browser console. Open `http://localhost:3000/trips.html` (sign in as `joey@example.com` / `password123`) and run:

```js
const s = await import('./js/selection.js');

const list = ['a', 'b'];
s.toggleId(list, 'c'); s.toggleId(list, 'a');
console.log('toggle:', JSON.stringify(list));

const items = [{id:'a'},{id:'b'},{id:'c'}];
console.log('present:', JSON.stringify(s.selectedPresent(['c','a','zz'], items)));

let seen = [];
const r = await s.runBulkDelete(items, async (id) => {
  seen.push(id);
  if (id === 'b') throw new Error('boom');
});
console.log('bulk:', JSON.stringify(r), 'attempted:', JSON.stringify(seen));
```

Expected output, exactly:
```
toggle: ["b","c"]
present: [{"id":"a"},{"id":"c"}]
bulk: {"deleted":2,"failed":1} attempted: ["a","b","c"]
```

`present` must be `a,c` in list order — not `c,a`, the order they were selected in. `attempted` must contain all three: a failure in the middle must not stop the run.

- [ ] **Step 3: Commit**

```bash
git add planner-web/js/selection.js
git commit -m "Add shared row-selection helpers for bulk delete"
```

---

### Task 2: Bulk delete on the Checklist tab

**Files:**
- Modify: `planner-web/js/pages/trip/checklist.js`
- Modify: `planner-web/trip.html`
- Modify: `planner-web/scss/components/_checklist-item.scss`

**Interfaces:**
- Consumes: `toggleId`, `selectedPresent`, `runBulkDelete` from Task 1.
- Produces: the `.check-row-wrap` / `.select-box` markup pattern that Task 3 mirrors for the timeline.

**Structural note — read before editing the markup.** A checklist row is a
`<button class="check-row">`. A checkbox **cannot** be nested inside a button:
it is invalid HTML and the click target is unreliable. So each row gets a
wrapper `<div class="check-row-wrap">` holding the checkbox *beside* the
button, and the row separator moves from the button to the wrapper. Do not
convert `.check-row` into a `<div>` — it would lose its keyboard semantics.

- [ ] **Step 1: Add selection state and the bulk action**

In `planner-web/js/pages/trip/checklist.js`, extend the import at the top:

```js
import { toast } from '../../toast.js';
import { category, timeRange, longDate, money, dateOf, timeOf } from '../../format.js';
import { toggleId, selectedPresent, runBulkDelete } from '../../selection.js';
```

Add state beside the existing filter state (after the `checkGroupBy: 'destination',` line):

```js
    // bulk selection
    checkSelectedIds: [],
    checkBulkOpen: false,
    checkBulkBusy: false,
```

Then add these methods to the same returned object, immediately before `categoryOf`:

```js
    // ── bulk selection ──────────────────────────────
    toggleCheckSelected(id) {
      toggleId(this.checkSelectedIds, id);
    },

    isCheckSelected(id) {
      return this.checkSelectedIds.includes(id);
    },

    /** Selected items that still exist — everything else derives from this. */
    get checkSelected() {
      return selectedPresent(this.checkSelectedIds, this.checklist);
    },

    /** How many of the selected items carry plans, for the confirm wording. */
    get checkSelectedPlanCount() {
      return this.checkSelected.reduce((sum, item) => sum + this.planCountFor(item), 0);
    },

    async confirmBulkDeleteChecks() {
      const doomed = this.checkSelected;
      if (!doomed.length) return;

      this.checkBulkBusy = true;
      try {
        const { deleted, failed } = await runBulkDelete(
          doomed, (id) => this.api.deleteCheck(this.trip.id, id));

        this.checkSelectedIds = [];
        this.checkBulkOpen = false;

        if (failed) toast.error(`Deleted ${deleted} — ${failed} could not be removed`);
        else toast.success(`Deleted ${deleted} checklist item${deleted === 1 ? '' : 's'}`);

        await this.reload();
      } finally {
        this.checkBulkBusy = false;
      }
    },
```

- [ ] **Step 2: Add the toolbar action**

In `planner-web/trip.html`, find the checklist toolbar's group-by select and add the bulk action directly after its closing `</select>`:

```html
          <button type="button" class="btn btn-sm btn-danger"
                  :disabled="!checkSelected.length"
                  @click="checkBulkOpen = true">
            Delete selected<span x-show="checkSelected.length"
                                 x-text="` (${checkSelected.length})`"></span>
          </button>
```

- [ ] **Step 3: Wrap each row and add its checkbox**

Still in `trip.html`, the checklist loop currently opens with
`<template x-for="item in group.items" :key="item.id">` followed directly by
`<button type="button" class="check-row" ...>`. Wrap that button:

```html
            <template x-for="item in group.items" :key="item.id">
              <div class="check-row-wrap">
                <label class="select-box" @click.stop>
                  <input type="checkbox" :checked="isCheckSelected(item.id)"
                         @change="toggleCheckSelected(item.id)"
                         :aria-label="`Select ${item.description}`">
                </label>
                <button type="button" class="check-row" :class="item.status === 'COMPLETED' ? 'is-done' : ''"
                        @click="openChecklistItem(item)">
```

and close the new `<div>` after that button's `</button>`, before `</template>`.

`@click.stop` on the label matters: without it, ticking the checkbox also opens the row's drawer.

- [ ] **Step 4: Add the confirm modal**

In `trip.html`, alongside the other confirmation modals (near the one with
`x-show="deletingEntry"`), add:

```html
      <!-- Bulk delete checklist items -->
      <div class="modal-backdrop" x-show="checkBulkOpen" x-cloak @click.self="checkBulkOpen = false"
           @keydown.escape.window="checkBulkOpen = false">
        <div class="modal" role="dialog" aria-modal="true" aria-labelledby="bulk-check-title">
          <div class="modal-header">
            <h2 class="modal-title" id="bulk-check-title">
              Delete <span x-text="checkSelected.length"></span>
              checklist item<span x-show="checkSelected.length !== 1">s</span>?
            </h2>
            <button type="button" class="modal-close" @click="checkBulkOpen = false"
                    aria-label="Close">✕</button>
          </div>
          <p class="small">
            This removes the selected items. Their plans
            <strong>stay on the itinerary</strong> — deleting a checklist item never
            deletes the planning done against it.
          </p>
          <p class="small muted" x-show="checkSelectedPlanCount">
            <span x-text="checkSelectedPlanCount"></span>
            plan<span x-show="checkSelectedPlanCount !== 1">s</span>
            will be kept and simply stop being linked.
          </p>
          <div class="modal-footer">
            <button type="button" class="btn" @click="checkBulkOpen = false">Cancel</button>
            <button type="button" class="btn btn-danger" :disabled="checkBulkBusy"
                    @click="confirmBulkDeleteChecks()"
                    x-text="checkBulkBusy ? 'Deleting…' : 'Delete selected'"></button>
          </div>
        </div>
      </div>
```

Note this modal does **not** carry `panel-switchable`: confirmations stay centred in both panel modes.

- [ ] **Step 5: Move the row separator to the wrapper**

In `planner-web/scss/components/_checklist-item.scss`, `.check-row` currently
owns `border-bottom`. Move it to the new wrapper so the checkbox sits inside
the same separated row.

Replace the `border-bottom` line inside `.check-row { … }` with nothing, and
replace the `.check-row:last-child` rule. The result should be:

```scss
.check-row-wrap {
  display: flex;
  align-items: flex-start;
  border-bottom: 1px solid var(--tp-border);
}

.check-row-wrap:last-child { border-bottom: 0; }
.check-row-wrap:hover { background: var(--tp-surface-alt); }

.select-box {
  display: flex;
  align-items: flex-start;
  padding: t.$space-3 0 t.$space-3 t.$space-4;
  cursor: pointer;
}

.select-box input { cursor: pointer; }

.check-row {
  display: flex;
  gap: t.$space-3;
  align-items: flex-start;
  width: 100%;
  padding: t.$space-3 t.$space-4;
  font: inherit;
  text-align: left;
  background: none;
  border: 0;
  cursor: pointer;
  color: var(--tp-text);
}

.check-row:focus-visible { @include m.focus-ring; outline-offset: -2px; }
```

Delete the old `.check-row:hover` rule — hover now belongs to the wrapper, so
the whole row highlights including the checkbox.

- [ ] **Step 6: Rebuild and verify**

```bash
cd planner-web && npm run css
```
Expected: exits 0, no Sass warnings.

Open a trip's Checklist tab (servers: web `:3000`, api `:8080`; sign in as
`joey@example.com` / `password123`). Verify each:

1. Every row shows a checkbox, and rows still show one separator line each.
2. Ticking a checkbox does **not** open the drawer.
3. With nothing ticked, "Delete selected" is disabled:
   ```js
   document.querySelector('.toolbar .btn-danger').disabled
   ```
   Expected: `true`.
4. Tick two rows — the button enables and reads `Delete selected (2)`.
5. Click it. The confirm says **"stay on the itinerary"**. Confirm, and exactly
   those two rows disappear; the others remain.
6. Clicking a row's body still opens the detail drawer.

- [ ] **Step 7: Commit**

```bash
git add planner-web/js/pages/trip/checklist.js planner-web/trip.html planner-web/scss/components/_checklist-item.scss
git commit -m "Add bulk delete to the checklist tab"
```

---

### Task 3: Bulk delete on the Itinerary tab

**Files:**
- Modify: `planner-web/js/pages/trip/itinerary.js`
- Modify: `planner-web/trip.html`
- Modify: `planner-web/scss/components/_timeline.scss`

**Interfaces:**
- Consumes: `toggleId`, `selectedPresent`, `runBulkDelete` from Task 1; the `.select-box` class from Task 2.
- Produces: nothing later tasks depend on.

- [ ] **Step 1: Swap per-row delete state for selection state**

In `planner-web/js/pages/trip/itinerary.js`, extend the imports:

```js
import { toast } from '../../toast.js';
import { category, timeRange, longDate, money, dateOf, timeOf } from '../../format.js';
import { toggleId, selectedPresent, runBulkDelete } from '../../selection.js';
```

Replace the `deletingEntry: null,` state line with:

```js
    // bulk selection — this tab has no per-row delete
    itinSelectedIds: [],
    itinBulkOpen: false,
    itinBulkBusy: false,
```

Delete the now-unused `askDeleteEntry(item)` and `confirmDeleteEntry()` methods
entirely, and add in their place:

```js
    // ── bulk selection ──────────────────────────────
    toggleItinSelected(id) {
      toggleId(this.itinSelectedIds, id);
    },

    isItinSelected(id) {
      return this.itinSelectedIds.includes(id);
    },

    get itinSelected() {
      return selectedPresent(this.itinSelectedIds, this.itinerary);
    },

    /** Selected entries carrying a cost — each takes a budget row with it. */
    get itinSelectedCostCount() {
      return this.itinSelected.filter((item) => item.cost).length;
    },

    async confirmBulkDeleteEntries() {
      const doomed = this.itinSelected;
      if (!doomed.length) return;

      this.itinBulkBusy = true;
      try {
        const { deleted, failed } = await runBulkDelete(
          doomed, (id) => this.api.deletePlan(this.trip.id, id));

        this.itinSelectedIds = [];
        this.itinBulkOpen = false;

        if (failed) toast.error(`Removed ${deleted} — ${failed} could not be removed`);
        else toast.success(`Removed ${deleted} itinerary item${deleted === 1 ? '' : 's'}`);

        await this.reload();
      } finally {
        this.itinBulkBusy = false;
      }
    },
```

- [ ] **Step 2: Add the toolbar action**

In `trip.html`, in the itinerary toolbar, directly after the
`Only items with a cost` checkbox label, add:

```html
          <button type="button" class="btn btn-sm btn-danger"
                  :disabled="!itinSelected.length"
                  @click="itinBulkOpen = true">
            Delete selected<span x-show="itinSelected.length"
                                 x-text="` (${itinSelected.length})`"></span>
          </button>
```

- [ ] **Step 3: Add a checkbox to each row and remove its Delete button**

In `trip.html`, inside the timeline loop, add the checkbox as the row's first
child — immediately after `<div class="timeline-entry">`:

```html
                  <label class="select-box select-box-tight">
                    <input type="checkbox" :checked="isItinSelected(item.id)"
                           @change="toggleItinSelected(item.id)"
                           :aria-label="`Select ${item.description}`">
                  </label>
```

Then replace the row's action span, which currently holds Edit **and** Delete,
with Edit alone:

```html
                  <span class="timeline-actions btn-row">
                    <button type="button" class="btn btn-sm" @click="openEditEntry(item)">Edit</button>
                  </span>
```

- [ ] **Step 4: Remove the per-row delete confirmation modal**

The modal with `x-show="deletingEntry"` is now unreachable — nothing sets that
state. Delete the whole `<div class="modal-backdrop" x-show="deletingEntry" …>`
block from `trip.html`, and add the bulk one beside the other confirmations:

```html
      <!-- Bulk delete itinerary entries -->
      <div class="modal-backdrop" x-show="itinBulkOpen" x-cloak @click.self="itinBulkOpen = false"
           @keydown.escape.window="itinBulkOpen = false">
        <div class="modal" role="dialog" aria-modal="true" aria-labelledby="bulk-itin-title">
          <div class="modal-header">
            <h2 class="modal-title" id="bulk-itin-title">
              Remove <span x-text="itinSelected.length"></span>
              itinerary item<span x-show="itinSelected.length !== 1">s</span>?
            </h2>
            <button type="button" class="modal-close" @click="itinBulkOpen = false"
                    aria-label="Close">✕</button>
          </div>
          <p class="small">This removes the selected entries from the itinerary.</p>
          <p class="small muted" x-show="itinSelectedCostCount">
            <span x-text="itinSelectedCostCount"></span>
            of them carr<span x-text="itinSelectedCostCount === 1 ? 'ies' : 'y'"></span>
            a cost, so the matching budget
            entr<span x-text="itinSelectedCostCount === 1 ? 'y' : 'ies'"></span>
            will be removed too.
          </p>
          <div class="modal-footer">
            <button type="button" class="btn" @click="itinBulkOpen = false">Cancel</button>
            <button type="button" class="btn btn-danger" :disabled="itinBulkBusy"
                    @click="confirmBulkDeleteEntries()"
                    x-text="itinBulkBusy ? 'Removing…' : 'Remove selected'"></button>
          </div>
        </div>
      </div>
```

- [ ] **Step 5: Style the checkbox cell**

In `planner-web/scss/components/_timeline.scss`, add after the
`.timeline-entry:last-child` rule:

```scss
// The timeline row already supplies its own padding, so the checkbox cell
// only needs to align with the first line of text.
.select-box-tight {
  padding: 0;
  flex: none;
  display: flex;
  align-items: flex-start;
  cursor: pointer;
}
```

- [ ] **Step 6: Rebuild and verify**

```bash
cd planner-web && npm run css
```
Expected: exits 0.

Confirm no per-row Delete survives on the itinerary:

```bash
grep -c 'askDeleteEntry\|deletingEntry' planner-web/trip.html planner-web/js/pages/trip/itinerary.js
```
Expected: `0` for both files.

Then in the browser, on a trip whose itinerary has at least one entry **with a
cost**:

1. Every timeline row shows a checkbox; no row shows a Delete button; Edit remains.
2. With nothing ticked, "Delete selected" is disabled.
3. Note the Budget tab's row count and total.
4. Back on Itinerary, tick the entry that has a cost and one that does not.
   The confirm says one of them carries a cost and its budget entry goes too.
5. Confirm. Both entries disappear.
6. Budget tab: the row count is **one** lower than in step 3, and the total has
   dropped by that entry's converted amount. This is the `BudgetSync.afterDelete`
   guardrail — if the budget row survived, bulk delete bypassed the cascade and
   the task has failed.

- [ ] **Step 7: Confirm the backend is untouched and its tests still pass**

```bash
git diff planner-api | wc -l
```
Expected: `0`.

```bash
cd planner-api && ./gradlew test
```
Expected: `BUILD SUCCESSFUL`. This change adds no backend code, so this run is a
regression check that nothing else drifted.

- [ ] **Step 8: Commit**

```bash
git add planner-web/js/pages/trip/itinerary.js planner-web/trip.html planner-web/scss/components/_timeline.scss
git commit -m "Add bulk delete to the itinerary tab, drop per-row delete"
```

---

## Verification against the brief

The brief's Done means, verbatim:

- [ ] Checklist: selecting N items and confirming removes exactly those N, and the confirm still states that their plans stay on the itinerary
- [ ] Itinerary: no per-row Delete button remains; deletion is only via selection
- [ ] Bulk-deleting an itinerary row that had a cost drops the Budget tab's row count by one and lowers the total accordingly
- [ ] Selecting nothing leaves the delete action disabled rather than deleting all
- [ ] `cd planner-api && ./gradlew test` passes
