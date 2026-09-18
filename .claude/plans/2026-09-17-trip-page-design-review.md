# Trip Page Design Review Fixes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `planner-web/trip.html` usable by keyboard, screen reader and phone, and fix the contrast and consistency findings from the 2026-09-17 design review.

**Architecture:** Frontend only. One new module (`js/dialog.js`) registers an Alpine `x-dialog` directive that every modal and the drawer opts into; everything else is markup attributes on `trip.html`, token/size changes in the SCSS partials, and small method additions in the tab factories under `js/pages/trip/`.

**Tech Stack:** Static HTML, Alpine.js (vendored, `vendor/alpine.min.js`), Sass (`npm run css`), ES modules, no bundler.

**Spec:** `.claude/briefs/2026-09-17-trip-page-design-review.md` — finding numbers (#1…#16) below refer to its table.

## Global Constraints

- **Git history is the user's.** A hook blocks Claude from creating commits, branches, merges, resets, tags or pushes. So: no worktrees, and every "Commit" step and Task 0/Task 8's branch and merge steps are skipped. All four agents edit the one working tree at the same time, which is safe only because of the file-ownership table below. Read-only git (`status`, `diff`, `log`) is fine. The user commits at the end.
- **Shared working tree.** Another agent may write to `trip.html` between your Read and your Edit; when an Edit fails because the file changed, re-read and retry. Never revert, reformat or "fix" a change you did not make, and never edit a file or `trip.html` region your task does not own. `npm run css` may briefly fail or produce odd output while another agent is mid-change in a different partial; re-run before concluding your SCSS is broken. `node_modules` already exists: skip `npm ci`.
- `git diff planner-api` stays empty.
- No new dependency and nothing new in `vendor/`.
- Colours only through CSS custom properties. A new token is always read as `var(--tp-new, var(--tp-existing))` so a theme that does not define it is unchanged.
- The checklist drawer body stays out of `x-if` (CLAUDE.md → Traps).
- Never target `x-for` output with `:first-child` / `:nth-child` (CLAUDE.md → Traps).
- Do **not** bump the stylesheet `?v=68` query in any HTML file and do **not** edit `CLAUDE.md` — both happen once, in Task 8, to avoid merge conflicts.
- `planner-web/assets/css/` is gitignored build output: build it to check your SCSS compiles, never commit it.
- Comment style: match the files you touch — comments say *why*, in full sentences.
- There is no frontend test suite. Each task's verification is (a) `npm run css` succeeds, (b) the grep/static checks given, and (c) browser checks that run in Task 8, where the signed-in session and the API's CORS origin (`:3000`) exist. A worktree cannot serve on `:3000` alongside the main checkout, so agents do not browser-test.
- Line numbers quoted from `trip.html` are from the commit made in Task 0. Find the markup by the quoted snippet, not by the number — another task may have shifted lines.

---

## Parallelism assessment

### What forces ordering

1. **Uncommitted work.** `trip.html`, `checklist.js`, `overview.js`, several HTML pages and `CLAUDE.md` have uncommitted edits, and `package-lock.json` is untracked. A worktree agent branches from `HEAD`, so without Task 0 every agent would start from stale files and the merge would silently revert that work.
2. **`trip.html` is shared.** Five tasks edit it. They are safe to parallelise only because each owns a *disjoint region* (table below) and git merges non-overlapping hunks cleanly.
3. **Task 8 (drawer unsaved-changes guard)** edits the exact lines Task 1 adds `x-dialog` to, and `checklist.js`, which Task 2 also edits. It runs after both.
4. **Shared single-line files.** `CLAUDE.md` and the `?v=` bump in 19 `<link>` tags would conflict in every branch, so they belong only to the integration step.

### File ownership

| File / region | T1 dialogs | T2 tick | T3 tabs | T4 budget table | T5 contrast & sizes | T6 toasts | T7 labels & dates | T8 integrate |
|---|---|---|---|---|---|---|---|---|
| `trip.html` tabs + `<section>` open tags (61-69, 72/130/191/266/380/530/722) | | | ✅ | | | | | |
| `trip.html` toolbars (199-208, 278-317, 388-418, 540-575) | | | | | | | ✅ | |
| `trip.html` checklist rows (336-373) | | ✅ | | | | | | |
| `trip.html` members "Added" cell (173-174) | | | | | | | ✅ | |
| `trip.html` budget table (652-717) | | | | ✅ | | | | |
| `trip.html` publish section body (728-866) | | | | | | | ✅ | |
| `trip.html` drawer + all modals (871-1777) | ✅ | | | | | | | ✅ (after T1) |
| `js/dialog.js` (new), `js/boot.js` | ✅ | | | | | | | |
| `js/pages/trip.js` | | | ✅ | | | | | |
| `js/pages/trip/checklist.js` | | ✅ | | | | | | ✅ (after T2) |
| `js/pages/trip/publish.js`, `members.js`, `js/format.js` | | | | | | | ✅ | |
| `js/toast.js`, `scss/components/_toast.scss`, `scss/themes/_dark.scss` | | | | | | ✅ | | |
| `scss/base/_reset.scss` | ✅ | | | | | | | |
| `scss/components/_checklist-item.scss`, `_timeline.scss` | | ✅ | | | | | | |
| `scss/layout/_tabs.scss` | | | ✅ | | | | | |
| `scss/components/_table.scss` | | | | ✅ | | | | |
| `scss/themes/_minima.scss`, `components/_badge.scss`, `_button.scss`, `_modal.scss`, `_budget-chart.scss` | | | | | ✅ | | | |
| `scss/pages/_trip.scss` | | | | | | | ✅ | |
| 19× `?v=68`, `CLAUDE.md`, brief status | | | | | | | | ✅ |

No file is owned by two parallel tasks except `trip.html`, where regions are disjoint with at least four untouched lines between them.

### Recommended waves

Seven parallel tasks are more agents than the work warrants — T5 and T6 are each ~15 minutes. Pair them by theme so each agent has a coherent brief and roughly equal size:

| Wave | Runs | Agent | Tasks | Why grouped |
|---|---|---|---|---|
| 0 | main session, **with you** | — | Task 0 | needs your commit approval |
| 1 | **4 agents in parallel**, `isolation: "worktree"` | A | Task 1 | largest; touches all 15 dialogs |
| | | B | Task 2 + Task 3 | both keyboard access on list/tab controls |
| | | C | Task 4 + Task 5 | both CSS sizing for phones |
| | | D | Task 6 + Task 7 | both copy / feedback polish |
| 2 | main session | — | Task 8 | merge A→B→C→D, drawer guard, `?v=` bump, docs, browser verification |

Wave 2 stays in the main session on purpose: it needs the signed-in Chrome session and it is where conflicts, if any, surface.

---

## Task 0: Baseline commit and integration branch (main session, sequential)

**Files:** none edited.

- [ ] **Step 1: Show the user what is uncommitted and get approval to commit it**

Run: `git status --short`
Expected: the modified/untracked list from the session start, including `planner-web/package-lock.json`.

- [ ] **Step 2: Commit the existing work as the baseline** (only after the user says yes)

```bash
git add -A
git commit -m "Checkpoint before trip page design fixes"
```

- [ ] **Step 3: Create the integration branch**

```bash
git switch -c design/trip-page-review
```

Every wave-1 agent branches from this commit.

---

## Task 1: Dialog focus management and scroll lock (#1, #9, #15) — Agent A

**Files:**
- Create: `planner-web/js/dialog.js`
- Modify: `planner-web/js/boot.js`
- Modify: `planner-web/scss/base/_reset.scss`
- Modify: `planner-web/trip.html` — the 15 `role="dialog"` elements (drawer at ~873, modals ~1090-1761)

**Interfaces:**
- Produces: Alpine directive `x-dialog="<expression>"` — place it on the `role="dialog"` element; the expression is the same one its backdrop's `x-show` uses. Produces `body[data-dialog-open]` while any dialog is open. Task 8 relies on the drawer carrying `x-dialog="openItem"`.

- [ ] **Step 1: Create `js/dialog.js`**

```js
/**
 * x-dialog: what a modal owes a keyboard and a screen reader.
 *
 * Put it on the role="dialog" element with the same expression its backdrop's
 * x-show uses. While that is truthy the dialog takes focus, keeps Tab inside
 * itself, and locks the page behind it from scrolling; when it goes falsy,
 * focus returns to whatever opened it. Without this, focus stayed on the
 * button behind the backdrop and Tab walked the page nobody could see.
 *
 * Dialogs can stack — the completion confirm opens over the checklist drawer —
 * so open dialogs are a stack and only the top one traps.
 */

const FOCUSABLE = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled]):not([type="hidden"])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]',
].join(',');

const stack = [];

/** Rendered, tabbable descendants. x-show hides with display:none, so offsetParent is the visibility test (CLAUDE.md, Traps). */
function tabbables(root) {
  return [...root.querySelectorAll(FOCUSABLE)]
    .filter((el) => el.tabIndex >= 0 && el.offsetParent !== null);
}

function trapTab(event) {
  if (event.key !== 'Tab' || !stack.length) return;
  const { el } = stack[stack.length - 1];
  const items = tabbables(el);
  if (!items.length) {
    event.preventDefault();
    el.focus();
    return;
  }
  const first = items[0];
  const last = items[items.length - 1];
  const active = document.activeElement;
  if (!el.contains(active)) {
    event.preventDefault();
    first.focus();
  } else if (event.shiftKey && (active === first || active === el)) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && active === last) {
    event.preventDefault();
    first.focus();
  }
}

function open(el) {
  if (stack.some((entry) => entry.el === el)) return;
  stack.push({ el, returnTo: document.activeElement });
  document.body.dataset.dialogOpen = '';

  // Two frames, not one: x-show reveals on the next frame, and a caller's own
  // focusWhenShown() lands on the frame after its $nextTick. Waiting one more
  // lets a field that caller chose keep the caret instead of being overridden.
  requestAnimationFrame(() => requestAnimationFrame(() => {
    if (el.contains(document.activeElement)) return;
    (tabbables(el)[0] || el).focus();
  }));
}

function close(el) {
  const index = stack.findIndex((entry) => entry.el === el);
  if (index === -1) return;
  const [{ returnTo }] = stack.splice(index, 1);
  if (!stack.length) delete document.body.dataset.dialogOpen;

  // The opener may have gone — a deleted row's Edit button, a tab switched
  // away from — and focusing a detached or hidden element does nothing.
  if (returnTo?.isConnected && returnTo.offsetParent !== null) returnTo.focus();
}

/** Call before Alpine loads: the directive is registered on alpine:init. */
export function registerDialogFocus() {
  document.addEventListener('keydown', trapTab);
  document.addEventListener('alpine:init', () => {
    window.Alpine.directive('dialog', (el, { expression }, { evaluateLater, effect, cleanup }) => {
      const isOpen = evaluateLater(expression);
      // Lets the dialog itself hold focus when it has nothing tabbable.
      if (!el.hasAttribute('tabindex')) el.setAttribute('tabindex', '-1');
      effect(() => isOpen((value) => (value ? open(el) : close(el))));
      cleanup(() => close(el));
    });
  });
}
```

- [ ] **Step 2: Register it in `js/boot.js` before Alpine is imported**

Add the import beside the others:

```js
import { registerDialogFocus } from './dialog.js';
```

and immediately before `await import('../vendor/alpine.min.js');`:

```js
  // Directives, like components, have to exist before Alpine starts.
  registerDialogFocus();
```

- [ ] **Step 3: Scroll lock, focus outline and reduced motion in `scss/base/_reset.scss`**

Replace `html { scroll-behavior: smooth; }` with:

```scss
// Smooth anchor scrolling is motion, so it waits for the reader to allow it.
@media (prefers-reduced-motion: no-preference) {
  html { scroll-behavior: smooth; }
}
```

Append:

```scss
// Set by js/dialog.js while any modal or drawer is open, so the page behind
// the backdrop cannot scroll.
body[data-dialog-open] { overflow: hidden; }

// A dialog only takes focus itself when it has nothing tabbable; the ring
// would outline the whole panel for no benefit.
[role="dialog"][tabindex="-1"]:focus { outline: none; }
```

- [ ] **Step 4: Add `x-dialog` to all 15 dialogs in `trip.html`**

On each `role="dialog"` element add `x-dialog="…"` with the expression from its backdrop's `x-show`:

| Dialog (heading) | Add to the `role="dialog"` element |
|---|---|
| Checklist drawer (`class="drawer"`) | `x-dialog="openItem"` |
| Edit trip | `x-dialog="editOpen"` |
| Delete this trip? | `x-dialog="deletingTrip"` |
| Leave / Remove member | `x-dialog="removingMember"` |
| Add / Edit destination | `x-dialog="destFormOpen"` |
| Delete N destinations? | `x-dialog="destBulkOpen"` |
| Add checklist item | `x-dialog="addCheckOpen"` |
| Add / Edit itinerary item | `x-dialog="entryOpen"` |
| Delete N checklist items? | `x-dialog="checkBulkOpen"` |
| Mark this item complete? | `x-dialog="completingItem"` |
| Remove N itinerary items? | `x-dialog="itinBulkOpen"` |
| Remove N expenses? | `x-dialog="budgetBulkOpen"` |
| Add / Edit expense | `x-dialog="expenseOpen"` |
| Publish this trip? | `x-dialog="confirmingPublish"` |
| Unpublish this trip? | `x-dialog="confirmingUnpublish"` |

Example — the drawer line becomes:

```html
        <div class="drawer" role="dialog" aria-modal="true" aria-labelledby="drawer-title"
             x-dialog="openItem" x-show="openItem" @click.stop>
```

- [ ] **Step 5: Name the three unnamed dialogs (#9)**

Remove member:

```html
        <div class="modal" role="dialog" aria-modal="true" aria-labelledby="remove-member-title"
             x-dialog="removingMember">
          <div class="modal-header">
            <h2 class="modal-title" id="remove-member-title"
                x-text="isSelf(removingMember || {}) ? 'Leave this trip?' : 'Remove this member?'"></h2>
```

Publish: `aria-labelledby="publish-title"` on the dialog, on the same line as `role="dialog"` (Step 6 greps for that), `id="publish-title"` on its `<h2 class="modal-title">`.
Unpublish: `aria-labelledby="unpublish-title"` on the dialog, `id="unpublish-title"` on its `<h2>`.

- [ ] **Step 6: Static checks**

Run: `cd planner-web && grep -c 'role="dialog"' trip.html && grep -c 'x-dialog=' trip.html`
Expected: `15` and `15`.

Run: `cd planner-web && grep -n 'role="dialog"' trip.html | grep -v aria-labelledby`
Expected: no output.

Run: `cd planner-web && npm ci && npm run css`
Expected: exits 0 (`npm ci` only needed once per worktree — `node_modules` is gitignored).

- [ ] **Step 7: Commit**

```bash
git add planner-web/js/dialog.js planner-web/js/boot.js planner-web/scss/base/_reset.scss planner-web/trip.html
git commit -m "Trip page: trap focus in dialogs, lock background scroll, name every dialog"
```

---

## Task 2: Checklist tick becomes a real button (#2, #4 for row checkboxes) — Agent B

**Files:**
- Modify: `planner-web/trip.html` — checklist row block (`<div class="check-row-wrap">` … its closing `</div>`, ~336-373)
- Modify: `planner-web/js/pages/trip/checklist.js` — `quickToggle` doc comment only
- Modify: `planner-web/scss/components/_checklist-item.scss`
- Modify: `planner-web/scss/components/_timeline.scss`

**Interfaces:**
- Consumes: `quickToggle(item, event)` and `askComplete(item, event)` (unchanged signatures).
- Produces: `.check-status` is a `<button>` that is a sibling of `.check-row`, not inside it. Task 8 does not depend on it beyond `checklist.js` merging cleanly.

- [ ] **Step 1: Move the tick out of the row and make it a button**

Replace the `<span class="check-status" …></span>` inside `.check-row` by deleting it there, and insert this between the `<label class="select-box">…</label>` and `<div class="check-row" …>`:

```html
                <!-- Beside the row, not in it, for the same reason as the
                     select box: the row owns an Edit button, and buttons
                     cannot nest. -->
                <button type="button" class="check-status"
                        :aria-pressed="item.status === 'COMPLETED'"
                        :aria-label="`Complete: ${item.description}`"
                        :title="item.status === 'COMPLETED' ? 'Mark as to do' : 'Mark complete'"
                        @click="quickToggle(item, $event)"
                        x-text="item.status === 'COMPLETED' ? '✔️' : '▫️'"></button>
```

- [ ] **Step 2: Update the `quickToggle` comment in `checklist.js`**

The method body is unchanged (`event.stopPropagation()` is now harmless — the button is no longer inside the clickable row — but keep it so a future move back does not reopen the drawer). Append one line to its doc comment:

```js
     * The tick is a <button> beside the row, so it is reachable by Tab and
     * announced with its pressed state.
```

- [ ] **Step 3: Style the button in `_checklist-item.scss`**

Replace the existing `.check-status { … }` rule with:

```scss
// A real button (it was a span, which no keyboard could reach), sitting
// between the select box and the row. Its top padding matches the row's, so
// the glyph stays on the first line of text.
.check-status {
  flex: none;
  display: flex;
  align-items: flex-start;
  justify-content: center;
  width: 32px;
  min-height: 44px;
  margin: 0;
  padding: t.$space-3 0 0;
  font: inherit;
  font-size: 0.95rem;
  line-height: 1.5;
  color: inherit;
  background: none;
  border: 0;
  cursor: pointer;
}

.check-status:focus-visible { @include m.focus-ring; outline-offset: -2px; }

// The tick used to sit inside the row's own padding; now it supplies that gap.
.check-status + .check-row { padding-left: t.$space-1; }
```

Append:

```scss
// Phones: a 13px checkbox and a 32px tick are both below a fingertip. The
// hit areas grow; the glyphs do not.
@include m.mobile {
  .select-box { min-width: 44px; min-height: 44px; padding-right: t.$space-2; }
  .check-status { width: 44px; }
}
```

- [ ] **Step 4: Grow the itinerary row's checkbox hit area in `_timeline.scss`**

Append to the existing `@include m.mobile { … }` block that holds `.timeline-entry { flex-wrap: wrap; }`:

```scss
  // Negative margin grows the hit area to 44px without moving the layout.
  .select-box-tight { padding: t.$space-3; margin: -#{t.$space-3}; }
```

- [ ] **Step 5: Static checks**

Run: `cd planner-web && grep -c 'span class="check-status"' trip.html; grep -c 'button type="button" class="check-status"' trip.html`
Expected: `0` then `1`.

Run: `cd planner-web && npm ci && npm run css`
Expected: exits 0.

- [ ] **Step 6: Commit**

```bash
git add planner-web/trip.html planner-web/js/pages/trip/checklist.js planner-web/scss/components/_checklist-item.scss planner-web/scss/components/_timeline.scss
git commit -m "Checklist: make the completion tick a focusable, named button"
```

---

## Task 3: Tabs follow the ARIA tabs pattern; phone tab bar shows overflow (#6, #7) — Agent B

**Files:**
- Modify: `planner-web/trip.html` — `<div class="tabs" role="tablist">` block (~61-69) and the seven `<section x-show="tab === '…'">` opening tags
- Modify: `planner-web/js/pages/trip.js` — `init`, `showTab`, new `onTabKeydown`, new `revealTab`
- Modify: `planner-web/scss/layout/_tabs.scss`

**Interfaces:**
- Consumes: `TABS` (array of `{ id, label }`), `selectTab(id)`, `showTab(id)`.
- Produces: element ids `tab-<id>` and `panel-<id>`; methods `onTabKeydown(event)` and `revealTab(id)` on the trip component.

- [ ] **Step 1: Tab list markup**

```html
      <div class="tabs" role="tablist" aria-label="Trip sections"
           @keydown="onTabKeydown($event)">
        <template x-for="t in tabs" :key="t.id">
          <button type="button" class="tab" role="tab"
                  :id="`tab-${t.id}`" :aria-controls="`panel-${t.id}`"
                  :aria-selected="tab === t.id" :tabindex="tab === t.id ? 0 : -1"
                  @click="selectTab(t.id)">
            <span x-text="t.label"></span>
            <span class="tab-count" x-show="countFor(t.id)" x-text="countFor(t.id)"></span>
          </button>
        </template>
      </div>
```

- [ ] **Step 2: Section opening tags**

Each of the seven becomes, e.g. for overview:

```html
      <section x-show="tab === 'overview'" role="tabpanel" id="panel-overview"
               aria-labelledby="tab-overview" tabindex="0">
```

Same for `members`, `destinations`, `checklist`, `itinerary`, `budget`, `publish` — only the id changes. Touch nothing else in the sections (Tasks 2, 4 and 7 own their bodies).

- [ ] **Step 3: Keyboard and reveal methods in `js/pages/trip.js`**

Add after `selectTab`:

```js
    /**
     * Left/Right/Home/End between tabs, per the WAI-ARIA tabs pattern. Only
     * the selected tab is a Tab stop, so Tab itself leaves the list for the
     * panel. Selection follows focus: every panel is already rendered, so
     * there is nothing to wait for.
     */
    onTabKeydown(event) {
      const ids = TABS.map((t) => t.id);
      const at = ids.indexOf(this.tab);
      const target = { ArrowRight: at + 1, ArrowLeft: at - 1, Home: 0, End: ids.length - 1 }[event.key];
      if (target === undefined) return;
      event.preventDefault();
      const id = ids[(target + ids.length) % ids.length];
      this.selectTab(id);
      this.$nextTick(() => document.getElementById(`tab-${id}`)?.focus());
    },

    /**
     * Scrolls the tab bar sideways so the selected tab is visible. On a phone
     * the bar is wider than the screen, and landing on #budget from a link
     * used to show a bar that ended at Checklist.
     *
     * scrollIntoView would also scroll the page vertically, which is wrong
     * when the tab was chosen from far down the Overview.
     */
    revealTab(id) {
      const button = document.getElementById(`tab-${id}`);
      const list = button?.parentElement;
      if (!list) return;
      const bar = list.getBoundingClientRect();
      const tab = button.getBoundingClientRect();
      const margin = 32; // clears the fade at the bar's right edge
      if (tab.left < bar.left) list.scrollLeft -= bar.left - tab.left + margin;
      else if (tab.right > bar.right - margin) list.scrollLeft += tab.right - bar.right + margin;
    },
```

In `showTab`, after `this.tab = id;` add:

```js
      this.$nextTick(() => this.revealTab(id));
```

In `init`, after `this.loading = false;` add:

```js
      // The tabs only render once loading is false.
      this.$nextTick(() => this.revealTab(this.tab));
```

- [ ] **Step 4: Tab bar CSS in `_tabs.scss`**

In `.tab:focus-visible` change to keep the ring inside the scroll box, which clips anything outside it:

```scss
.tab:focus-visible { @include m.focus-ring; outline-offset: -2px; }
```

Append:

```scss
// Phones: the bar scrolls sideways, and with the scrollbar hidden nothing said
// so. The fade is the cue; the end padding lets the last tab scroll clear of it.
@include m.mobile {
  .tabs {
    padding-right: t.$space-6;
    scrollbar-width: none;
    -webkit-mask-image: linear-gradient(to right, #000 calc(100% - #{t.$space-6}), transparent);
    mask-image: linear-gradient(to right, #000 calc(100% - #{t.$space-6}), transparent);
  }
  .tabs::-webkit-scrollbar { display: none; }
}
```

(`#000` here is a mask alpha, not a colour — the only literal allowed in this change.)

- [ ] **Step 5: Static checks**

Run: `cd planner-web && grep -c 'role="tabpanel"' trip.html`
Expected: `7`.

Run: `cd planner-web && node --check js/pages/trip.js`
Expected: no output (valid syntax).

Run: `cd planner-web && npm run css`
Expected: exits 0.

- [ ] **Step 6: Commit**

```bash
git add planner-web/trip.html planner-web/js/pages/trip.js planner-web/scss/layout/_tabs.scss
git commit -m "Trip tabs: ARIA tabpanels, arrow-key navigation, visible overflow on phones"
```

---

## Task 4: Budget table becomes cards on phones (#3, #4 for table checkboxes) — Agent C

**Files:**
- Modify: `planner-web/trip.html` — budget `<div class="table-wrap" x-show="filteredBudgetItems.length">` block (~652-717)
- Modify: `planner-web/scss/components/_table.scss`

**Interfaces:**
- Produces: CSS modifier `.table-cards` on a `.table`, driven by `data-label` on cells plus `cell-title`, `cell-chips`, `cell-emphasis` classes. Reusable later for the Destinations table (out of scope now).

- [ ] **Step 1: Mark up the budget table**

Change `<table class="table">` to `<table class="table table-cards">` and give the body cells labels/classes. The `<tbody>` row becomes:

```html
                  <tr>
                    <td class="select-cell">
                      <input type="checkbox" :checked="isBudgetSelected(item.id)"
                             @change="toggleBudgetSelected(item.id)"
                             :aria-label="`Select ${item.description}`">
                    </td>
                    <td class="wrap cell-title" x-text="item.description"></td>
                    <!-- Same icon-and-label chip the checklist and itinerary
                         rows use, so a category reads the same everywhere. -->
                    <td class="cell-chips">
                      <div class="chip-group">
                        <span class="chip">
                          <span x-text="categoryOf(item.category).icon"></span>
                          <span x-text="categoryOf(item.category).label"></span>
                        </span>
                        <template x-for="label in countryLabels(item.countryCodes)" :key="label">
                          <span class="chip" x-text="label"></span>
                        </template>
                      </div>
                    </td>
                    <!-- (keep the existing comment about amount vs share) -->
                    <td class="num" data-label="Amount" x-text="fmt(item.amount, item.currency)"></td>
                    <td class="num cell-emphasis" data-label="Your share"
                        x-text="convertedShareOf(item) == null
                                ? '—' : fmt(convertedShareOf(item), budget.totalsCurrency)"></td>
                    <!-- (keep the existing pending comment) -->
                    <td data-label="Status">
                      <span class="chip" :class="item.status === 'PENDING' ? 'chip-pending' : 'chip-charged'"
                            x-text="statusLabel(item)"></span>
                    </td>
                    <td class="muted" data-label="Date" x-text="expenseDate(item)"></td>
                    <td class="tiny muted" data-label="Source">
                      <span x-show="item.itineraryItemId"
                            :title="sourcePlan(item)">From plan</span>
                      <span x-show="!item.itineraryItemId">Manual</span>
                    </td>
                    <td class="actions">
                      <span class="btn-row">
                        <button type="button" class="btn btn-sm"
                                @click="openEditExpense(item)">Edit</button>
                      </span>
                    </td>
                  </tr>
```

Keep the two existing HTML comments where they were. The desktop header `Shared Amount` stays as is; "Your share" is only the phone label.

- [ ] **Step 2: Card layout in `_table.scss`**

Append:

```scss
// Phones: a table whose money sits in the fourth column puts the money
// off-screen. .table-cards turns each row into a card — title and Edit on top,
// chips under the title, then one "label … value" line per data-label cell.
//
// Every selector carries .table.table-cards so it outranks the .table .num /
// .table .wrap / .table .select-cell rules above without !important.
@include m.mobile {
  .table-wrap:has(> .table-cards) { overflow-x: visible; }

  .table.table-cards,
  .table.table-cards tbody { display: block; }

  .table.table-cards thead { @include m.visually-hidden; }

  .table.table-cards tbody tr {
    display: grid;
    grid-template-columns: 44px minmax(0, 1fr) auto;
    column-gap: t.$space-2;
    row-gap: 2px;
    padding: t.$space-3 t.$space-3 t.$space-3 0;
    border-bottom: 1px solid var(--tp-border);
  }

  .table.table-cards tbody tr:last-child { border-bottom: 0; }

  .table.table-cards td {
    display: block;
    grid-column: 2;
    min-width: 0;
    padding: 0;
    text-align: left;
    white-space: normal;
    border: 0;
  }

  .table.table-cards .select-cell {
    grid-column: 1;
    grid-row: 1 / span 8;
    width: auto;
    padding-top: 2px;
    text-align: center;
  }

  .table.table-cards .select-cell input { width: 20px; height: 20px; }

  .table.table-cards .cell-title { grid-row: 1; font-weight: 600; }
  .table.table-cards .cell-chips { margin: t.$space-1 0; }

  .table.table-cards .actions {
    grid-column: 3;
    grid-row: 1 / span 2;
    text-align: right;
  }

  .table.table-cards td[data-label] {
    display: flex;
    gap: t.$space-2;
    justify-content: space-between;
    font-size: 0.85rem;
    font-variant-numeric: tabular-nums;
  }

  .table.table-cards td[data-label]::before {
    content: attr(data-label);
    color: var(--tp-muted);
  }

  .table.table-cards .cell-emphasis { font-weight: 600; color: var(--tp-text); }
}
```

- [ ] **Step 3: Static checks**

Run: `cd planner-web && grep -c 'data-label=' trip.html`
Expected: `5`.

Run: `cd planner-web && npm ci && npm run css && grep -c 'table-cards' assets/css/dark.css`
Expected: exits 0 and a count ≥ 10.

- [ ] **Step 4: Commit**

```bash
git add planner-web/trip.html planner-web/scss/components/_table.scss
git commit -m "Budget: show expenses as cards on phones so the amounts stay on screen"
```

---

## Task 5: Minima contrast, touch sizes and minimum text sizes (#4, #5, #16) — Agent C

**Files:**
- Modify: `planner-web/scss/themes/_minima.scss`
- Modify: `planner-web/scss/components/_badge.scss`
- Modify: `planner-web/scss/components/_button.scss`
- Modify: `planner-web/scss/components/_modal.scss`
- Modify: `planner-web/scss/components/_budget-chart.scss`

**Interfaces:**
- Produces: optional token `--tp-warning-ink` (falls back to `--tp-warning`). Only Minima defines it.

- [ ] **Step 1: Minima tokens**

In `_minima.scss`, change `--tp-success: #4d7c0f;` and add the new token under `--tp-warning`:

```scss
  // Darkened from #4d7c0f: "Completed" and "Published" sit on --tp-surface-alt
  // chips, where the old value was 4.32:1. Now 5.55:1 there, 6.3:1 on surface.
  --tp-success: #3f6a0a;
  --tp-danger: #b91c1c;
  --tp-danger-bg: #fef2f2;
  --tp-warning: #ca8a04;
  // Pending text. #ca8a04 stays for borders, where 3:1 is enough, but as text
  // it was 2.89:1 — on the one chip the budget most needs read. 5.13:1 on the
  // chip background.
  --tp-warning-ink: #8a5a00;
```

- [ ] **Step 2: Chips read the ink token, and grow on phones (`_badge.scss`)**

```scss
.badge-pending { color: var(--tp-warning-ink, var(--tp-warning)); border-color: var(--tp-warning); }
```

```scss
.chip-pending { color: var(--tp-warning-ink, var(--tp-warning)); border-color: var(--tp-warning); }
```

In `.chip`, change `font-size: 0.75rem;` to `font-size: 0.8125rem;` (13px; 12px was below a comfortable minimum for labels people filter by).

Append:

```scss
// Phones: filter chips are tapped, not clicked. 36px plus the wider gap keeps
// neighbours from being hit by mistake without making a row of six chips
// wrap into four lines.
@include m.mobile {
  button.chip { min-height: 36px; padding: 6px 12px; }
  .chip-group { gap: t.$space-2; }
}
```

Add `@use "../abstracts/mixins" as m;` at the top of `_badge.scss` (it currently only uses tokens).

- [ ] **Step 3: Buttons (`_button.scss`)**

Append:

```scss
// Phones: 44px for a full button, 40px for the small per-row ones, which sit
// in rows with their own padding around them.
@include m.mobile {
  .btn { min-height: 44px; }
  .btn-sm { min-height: 40px; }
}
```

- [ ] **Step 4: Close button (`_modal.scss`)**

Replace the `.modal-close { … }` rule with:

```scss
.modal-close {
  display: inline-flex;
  flex: none;
  align-items: center;
  justify-content: center;
  min-width: 32px;
  min-height: 32px;
  padding: 0;
  font-size: 1.1rem;
  line-height: 1;
  color: var(--tp-muted);
  background: none;
  border: 0;
  border-radius: var(--tp-radius-sm);
  cursor: pointer;
}
```

Add inside the existing `@include m.mobile { … }` block of `_modal.scss`:

```scss
  .modal-close { min-width: 44px; min-height: 44px; }
```

(The drawer's ✕ reuses `.modal-close`, so this covers it.)

- [ ] **Step 5: Rates note (`_budget-chart.scss`)**

In `.budget-rates-note` change `font-size: 0.72rem;` to `font-size: 0.78rem;`.

- [ ] **Step 6: Verify contrast of the new values**

Run:

```bash
python3 - <<'EOF'
def lum(h):
    c=[int(h.lstrip('#')[i:i+2],16)/255 for i in (0,2,4)]
    c=[x/12.92 if x<=0.03928 else ((x+0.055)/1.055)**2.4 for x in c]
    return 0.2126*c[0]+0.7152*c[1]+0.0722*c[2]
def cr(a,b):
    a,b=sorted([lum(a),lum(b)],reverse=True); return round((a+0.05)/(b+0.05),2)
for fg in ['#8a5a00','#3f6a0a']:
    print(fg, cr(fg,'#fffdf7'), cr(fg,'#f7eed8'))
print('toast-success text', cr('#fffdf7','#3f6a0a'))
EOF
```

Expected: every number ≥ 4.5 (`#8a5a00 5.83 5.13`, `#3f6a0a 6.3 5.55`, `toast-success text 6.3`).

Run: `cd planner-web && npm run css`
Expected: exits 0.

- [ ] **Step 7: Commit**

```bash
git add planner-web/scss/themes/_minima.scss planner-web/scss/components/_badge.scss planner-web/scss/components/_button.scss planner-web/scss/components/_modal.scss planner-web/scss/components/_budget-chart.scss
git commit -m "Minima contrast for pending and success; phone-sized touch targets"
```

---

## Task 6: Error toasts persist; dark toast text (#10, latent dark toast) — Agent D

**Files:**
- Modify: `planner-web/js/toast.js`
- Modify: `planner-web/scss/components/_toast.scss`
- Modify: `planner-web/scss/themes/_dark.scss`

**Interfaces:**
- Consumes/Produces: `toast.show(message)`, `toast.success(message)`, `toast.error(message)` — signatures unchanged; every existing caller keeps working.

- [ ] **Step 1: Rewrite `js/toast.js`**

```js
/**
 * Brief confirmations and failures, bottom-centre.
 *
 * Confirmations fade after a few seconds. Errors stay until dismissed: a
 * server message takes longer than four seconds to read, and nothing else on
 * the page records it. The two also go to different live regions — polite
 * for "Saved", assertive for "Could not save" — which have to exist before
 * anything is put in them, or a screen reader never announces the first one.
 */

const LIFETIME_MS = 4000;
const MAX_ERRORS = 3;

function regions() {
  let node = document.querySelector('.toast-stack');
  if (!node) {
    node = document.createElement('div');
    node.className = 'toast-stack';
    node.innerHTML = '<div class="toast-region" role="status" aria-live="polite"></div>'
                   + '<div class="toast-region" role="alert"></div>';
    document.body.appendChild(node);
  }
  const [polite, assertive] = node.children;
  return { polite, assertive };
}

function show(message, variant) {
  const toast = document.createElement('div');
  toast.className = `toast${variant ? ` toast-${variant}` : ''}`;

  if (variant !== 'error') {
    toast.textContent = message;
    regions().polite.appendChild(toast);
    setTimeout(() => toast.remove(), LIFETIME_MS);
    return;
  }

  const text = document.createElement('span');
  text.textContent = message;
  const dismiss = document.createElement('button');
  dismiss.type = 'button';
  dismiss.className = 'toast-close';
  dismiss.setAttribute('aria-label', 'Dismiss');
  dismiss.textContent = '✕';
  dismiss.addEventListener('click', () => toast.remove());
  toast.append(text, dismiss);

  const { assertive } = regions();
  assertive.appendChild(toast);
  // A run of failures should not wallpaper the screen; the oldest go first.
  while (assertive.children.length > MAX_ERRORS) assertive.firstElementChild.remove();
}

export const toast = {
  show: (message) => show(message),
  success: (message) => show(message, 'success'),
  error: (message) => show(message, 'error'),
};
```

- [ ] **Step 2: Styles in `_toast.scss`**

Change `.toast`'s `color: var(--tp-on-accent);` to:

```scss
  // Its own token, falling back to on-accent: the Dark theme's toast is navy
  // while its accent is light, so on-accent there is dark ink on dark (1.28:1).
  color: var(--tp-on-toast, var(--tp-on-accent));
```

Replace the two variant rules with:

```scss
// Coloured variants sit on the semantic fills, which pair with on-accent in
// every theme regardless of what --tp-on-toast says.
.toast-error { color: var(--tp-on-accent); background: var(--tp-danger); }
.toast-success { color: var(--tp-on-accent); background: var(--tp-success); }

// Not display:contents — Chrome has dropped the role of such elements from
// the accessibility tree, which would silence both live regions.
.toast-region { display: flex; flex-direction: column; gap: 8px; }

// The stack ignores the pointer so it never blocks the page; a dismissable
// error has to take it back.
.toast-error {
  display: flex;
  gap: 12px;
  align-items: center;
  pointer-events: auto;
}

.toast-close {
  flex: none;
  min-width: 32px;
  min-height: 32px;
  padding: 0;
  font: inherit;
  line-height: 1;
  color: inherit;
  background: none;
  border: 0;
  border-radius: var(--tp-radius-sm);
  cursor: pointer;
}

.toast-close:focus-visible { outline: 2px solid currentColor; outline-offset: -2px; }
```

- [ ] **Step 3: Dark token in `_dark.scss`**

Under `--tp-toast-bg: #1c2a50;` add:

```scss
  --tp-on-toast: #e8f0ff;  // 12.3:1 on the toast; on-accent is dark ink here
```

- [ ] **Step 4: Static checks**

Run: `cd planner-web && node --check js/toast.js && grep -rn "toast\.\(show\|success\|error\)(" js | wc -l`
Expected: no syntax output, and a non-zero count (callers unchanged).

Run: `cd planner-web && npm ci && npm run css`
Expected: exits 0.

- [ ] **Step 5: Commit**

```bash
git add planner-web/js/toast.js planner-web/scss/components/_toast.scss planner-web/scss/themes/_dark.scss
git commit -m "Toasts: errors persist until dismissed and announce assertively"
```

---

## Task 7: Group labels, date formats, request status, publish spacing (#11, #13, #14, #16) — Agent D

**Files:**
- Modify: `planner-web/js/format.js`
- Modify: `planner-web/js/pages/trip/publish.js`
- Modify: `planner-web/js/pages/trip/members.js`
- Modify: `planner-web/trip.html` — toolbars (~278-317, ~388-418, ~540-575), members "Added" cell (~173-174), publish section body (~728-866)
- Modify: `planner-web/scss/pages/_trip.scss`

**Interfaces:**
- Produces: `dateTimeLabel(isoInstant)` → `"17 Sep 2026, 07:43"` and `dateLabel(isoInstant)` → `"14 Sep 2026"` in `format.js`; `requestStatusLabel(status)` on the publish tab; `addedLabel(member)` on the members tab.

- [ ] **Step 1: Date helpers in `format.js`**

Add after `longDate`:

```js
/**
 * An instant ("2026-09-17T05:43:30Z") in the reader's own timezone, in the
 * same day-month-year order as every other date on the page. toLocaleString()
 * gave "17/09/2026, 07:43:30" beside "24 Oct" — three formats on one screen,
 * with seconds nobody needs.
 */
export function dateTimeLabel(iso) {
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  const hh = String(date.getHours()).padStart(2, '0');
  const mm = String(date.getMinutes()).padStart(2, '0');
  return `${dateLabel(iso)}, ${hh}:${mm}`;
}

/** The date part of an instant, local time: "14 Sep 2026". */
export function dateLabel(iso) {
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  return `${date.getDate()} ${MONTHS_SHORT[date.getMonth()]} ${date.getFullYear()}`;
}
```

(Not `parseDate`: that reads the first ten characters as a local date, which is right for a trip date and wrong for an instant stamped in UTC.)

- [ ] **Step 2: Publish labels in `publish.js`**

Add to the imports:

```js
import { dateTimeLabel } from '../../format.js';
```

Replace the three label methods and add the status label:

```js
    publishedAtLabel() {
      return dateTimeLabel(this.publish?.publishedAt);
    },

    requestedAtLabel(request) {
      return dateTimeLabel(request.requestedAt);
    },

    /** The date a decided request is listed under — the same one it sorts on. */
    decidedAtLabel(request) {
      return dateTimeLabel(request.decidedAt || request.requestedAt);
    },

    /** PublishRequest.Status as words; the enum name is not copy. */
    requestStatusLabel(status) {
      return { PENDING: 'Pending', APPROVED: 'Approved', REJECTED: 'Rejected',
               CANCELLED: 'Cancelled' }[status] || status;
    },
```

- [ ] **Step 3: Member added date in `members.js`**

Add `import { dateLabel } from '../../format.js';` and a method on the returned object:

```js
    addedLabel(member) {
      return dateLabel(member.invitedAt);
    },
```

- [ ] **Step 4: `trip.html` — members, publish**

Members "Added" cell:

```html
                  <td class="muted tiny" x-text="addedLabel(member)"></td>
```

Recent requests badge:

```html
              <span class="badge" x-text="requestStatusLabel(request.status)"></span>
```

"Your own page" block — replace `<div style="margin-top:12px">` with `<div class="publish-own-page">` and its `<p class="small muted" style="margin-bottom:4px">` with `<p class="small muted">`.

- [ ] **Step 5: `trip.html` — group labels on the toolbars (#11)**

Give every toolbar label an id and its chip group `role="group"` + `aria-labelledby`:

| Toolbar | Label text | id | chip-group gets |
|---|---|---|---|
| Checklist | Category | `check-category-label` | `role="group" aria-labelledby="check-category-label"` |
| Checklist | Status | `check-status-label` | `role="group" aria-labelledby="check-status-label"` |
| Itinerary | Category | `itin-category-label` | `role="group" aria-labelledby="itin-category-label"` |
| Itinerary | Show | `itin-show-label` | `role="group" aria-labelledby="itin-show-label"` |
| Budget | Category | `budget-category-label` | `role="group" aria-labelledby="budget-category-label"` |

Example:

```html
          <span class="toolbar-label" id="check-category-label">Category</span>
          <div class="chip-group" role="group" aria-labelledby="check-category-label">
```

Leave the "Delete selected" buttons exactly as they are (see the brief: #12 was dropped).

- [ ] **Step 6: `_trip.scss`**

In `.toolbar-label` change `font-size: 0.75rem;` to `font-size: 0.8125rem;`.

Append:

```scss
// The personal-page link and its explanation, between the URL and the main
// button row; it used to butt straight against that row.
.publish-own-page { margin: t.$space-3 0; }
.publish-own-page p { margin-bottom: t.$space-1; }
```

- [ ] **Step 7: Static checks**

Run: `cd planner-web && node --check js/format.js && node --check js/pages/trip/publish.js && node --check js/pages/trip/members.js`
Expected: no output.

Run: `cd planner-web && grep -c 'toLocaleString\|toLocaleDateString' trip.html js/pages/trip/publish.js`
Expected: `trip.html:0` and `js/pages/trip/publish.js:0`.

Run: `cd planner-web && node -e "import('./js/format.js').then(f=>console.log(f.dateTimeLabel('2026-09-17T05:43:30Z'), '|', f.dateLabel('2026-09-14T10:00:00Z')))"`
Expected: `17 Sep 2026, HH:43 | 14 Sep 2026` (hour depends on the machine's timezone). Node 22+ detects the ES module syntax; on older Node, skip this check.

Run: `cd planner-web && npm ci && npm run css`
Expected: exits 0.

- [ ] **Step 8: Commit**

```bash
git add planner-web/js/format.js planner-web/js/pages/trip/publish.js planner-web/js/pages/trip/members.js planner-web/trip.html planner-web/scss/pages/_trip.scss
git commit -m "Trip page: one date format, readable request status, labelled filter groups"
```

---

## Task 8: Integrate, drawer unsaved-changes guard, verify (#8) — main session

**Files:**
- Modify: `planner-web/js/pages/trip/checklist.js`
- Modify: `planner-web/trip.html` — drawer backdrop, header ✕, new discard prompt
- Modify: all 19 `?v=68` stylesheet links in `planner-web/*.html`
- Modify: `CLAUDE.md`
- Modify: `.claude/briefs/2026-09-17-trip-page-design-review.md` (status)

**Interfaces:**
- Consumes: `x-dialog="openItem"` on the drawer (Task 1); `.check-status` button (Task 2); `closeDrawer()` (existing).
- Produces: `drawerDirty` getter, `requestCloseDrawer()`, `drawerDiscardAsk` state.

- [ ] **Step 1: Merge the four agent branches in order**

```bash
git merge --no-ff <agent-A-branch>   # Task 1
git merge --no-ff <agent-B-branch>   # Tasks 2, 3
git merge --no-ff <agent-C-branch>   # Tasks 4, 5
git merge --no-ff <agent-D-branch>   # Tasks 6, 7
```

Expected: no conflicts. If `trip.html` conflicts, keep both sides — every task's edits are additive to its own region.

- [ ] **Step 2: Guard state and methods in `checklist.js`**

Add `drawerDiscardAsk: false,` beside the other drawer state (next to `deletingCheck`). In `openChecklistItem`, add `this.drawerDiscardAsk = false;`. In `closeDrawer`, add `this.drawerDiscardAsk = false;`.

Add after `closeDrawer`:

```js
    /**
     * True when closing now would lose something typed: a Details field that
     * no longer matches the stored item, or a Plan form left open.
     */
    get drawerDirty() {
      const item = this.openItem;
      if (!item) return false;
      const form = this.drawerForm;
      const sameCountries = [...form.countryCodes].sort().join()
                         === [...(item.countryCodes || [])].sort().join();
      return this.planOpen
          || form.description !== (item.description || '')
          || form.note !== (item.note || '')
          || form.category !== item.category
          || !sameCountries;
    },

    /**
     * Escape, the backdrop and ✕ come through here; closeDrawer() stays the
     * unconditional close for the code paths that have already saved or
     * deleted. Those three used to discard a half-written note without a word.
     */
    requestCloseDrawer() {
      // Escape meant for the completion confirm on top must not reach here.
      if (this.completingItem) return;
      if (this.drawerDirty && !this.drawerDiscardAsk) {
        this.drawerDiscardAsk = true;
        return;
      }
      this.closeDrawer();
    },
```

- [ ] **Step 3: Wire it in `trip.html`**

Drawer backdrop:

```html
      <div class="drawer-backdrop panel-switchable" x-show="openItem" x-cloak @click.self="requestCloseDrawer()"
           @keydown.escape.window="requestCloseDrawer()">
```

Drawer ✕: `@click="requestCloseDrawer()"`.

Directly under `<div class="alert alert-error" x-show="drawerError" …></div>` in the drawer, add:

```html
              <div class="alert alert-warning" role="alert" x-show="drawerDiscardAsk" x-cloak>
                You have unsaved changes to this item.
                <div class="row row-end" style="margin-top:8px">
                  <button type="button" class="btn btn-sm" @click="drawerDiscardAsk = false">Keep editing</button>
                  <button type="button" class="btn btn-sm btn-danger" @click="closeDrawer()">Discard</button>
                </div>
              </div>
```

`showTab` still calls `closeDrawer()` directly — switching tabs is a navigation, and the hashchange route cannot be paused for a prompt. Leave it.

- [ ] **Step 4: Bump stylesheet versions and build**

```bash
cd planner-web && sed -i '' 's/?v=68/?v=69/g' *.html && grep -c '?v=69' *.html && npm run css
```

Expected: 19 total occurrences across the pages; css build exits 0.

- [ ] **Step 5: Browser verification (signed in, LATAM trip, `?cb=1` on the URL)**

Close and reopen the tab first (CLAUDE.md → Traps: the ES module graph survives a reload). Run in the console on `trip.html?id=…&cb=1#checklist`:

```js
// 1. dialog focus: open the first checklist item, then check focus is inside
document.querySelector('.check-row-wrap .btn-sm').click();
await new Promise(r => setTimeout(r, 150));
const drawer = document.querySelector('.drawer');
({ focusInside: drawer.contains(document.activeElement),
   bodyLocked: document.body.hasAttribute('data-dialog-open') })
```
Expected: `{ focusInside: true, bodyLocked: true }`.

Then by hand, with the keyboard:
- [ ] Tab 30 times in the drawer: focus never leaves it. Shift+Tab from the first field wraps to the last button.
- [ ] Type in Note, press Escape: the "unsaved changes" prompt appears; Keep editing keeps it open; Escape again closes. Focus returns to the Edit button that opened it.
- [ ] Tab to a checklist tick: it shows a focus ring; Enter opens "Mark this item complete?"; Cancel returns focus to the tick.
- [ ] Focus the Checklist tab; ArrowRight moves to Itinerary and shows it; End goes to Publish; Home to Overview.
- [ ] Every dialog opened from each tab (Edit trip, Delete trip, Add destination, Add checklist item, Add itinerary item, Add expense, the three bulk deletes via one selected row then Cancel, Remove member then Cancel, Unpublish then Cancel): focus lands inside, Tab stays inside, Escape closes, focus returns. **Do not press the confirm buttons.**

```js
// 2. accessible names
[...document.querySelectorAll('[role=dialog]')].filter(d => !document.getElementById(d.getAttribute('aria-labelledby'))?.textContent.trim()).length
```
Expected: `0` (open Remove member / Publish once first if their titles are empty until opened — they are `x-text`).

```js
// 3. error toast persists
const { toast } = await import('/js/toast.js');
toast.error('Test failure'); await new Promise(r => setTimeout(r, 5000));
document.querySelectorAll('.toast-error').length
```
Expected: `1`; clicking its ✕ removes it.

- [ ] **Step 6: Phone width checks** — resize the window to 400×860 (Chrome's floor may be 500):

```js
const vis = el => el.offsetParent !== null;
({
  budgetOverflow: [...document.querySelectorAll('.table-wrap')].filter(vis)
                    .map(w => w.scrollWidth <= w.clientWidth),
  chipMinH: Math.min(...[...document.querySelectorAll('button.chip')].filter(vis).map(e => e.getBoundingClientRect().height)),
  btnSmMinH: Math.min(...[...document.querySelectorAll('.btn-sm')].filter(vis).map(e => e.getBoundingClientRect().height)),
  tickH: [...document.querySelectorAll('.check-status')].filter(vis).map(e => e.getBoundingClientRect().height)[0],
})
```
Expected on `#budget`: `budgetOverflow` all `true`, `chipMinH ≥ 36`, `btnSmMinH ≥ 40`. On `#checklist`: `tickH ≥ 44`.

- [ ] Load `#budget` directly at phone width: the Budget tab is visible in the bar, not cut off.
- [ ] Screenshot `#budget`: each card shows description, Amount, Your share, Status, Date, Source and Edit.

- [ ] **Step 7: Theme checks** — switch to Minima and Y2K from the header, then back to Dark (the user's saved theme):
- [ ] Minima: Pending chip on an expense edited to unticked "already charged" (then re-tick it — do not leave data changed) reads dark amber, not yellow.
- [ ] Y2K: chips, toasts and the Pending chip look as before (no `--tp-warning-ink` / `--tp-on-toast` defined there).
- [ ] Dark: success and error toasts unchanged.

- [ ] **Step 8: Guardrail checks**

Run: `git diff <task-0-sha> --stat -- planner-api` (the sha of the Task 0 checkpoint commit)
Expected: no output.

Run: `git diff <task-0-sha> --stat -- planner-web/vendor planner-web/package.json`
Expected: no output.

- [ ] **Step 9: Document in `CLAUDE.md`**

Under **Frontend architecture**, after the "Detail views are popup or side panel" paragraph, add:

```markdown
**Every modal and drawer carries `x-dialog`.** `js/dialog.js` registers it
before Alpine starts; put it on the `role="dialog"` element with the same
expression as the backdrop's `x-show`. While truthy the dialog takes focus,
traps Tab, and sets `body[data-dialog-open]` to stop the page scrolling behind
it; when it goes falsy focus returns to whatever opened it. Dialogs stack (the
completion confirm opens over the checklist drawer) and only the top one traps.
It waits two frames before focusing so a caller's own `focusWhenShown()` wins.
**A new dialog without `x-dialog` leaves focus behind the backdrop** — the
same kind of silent opt-in as `panel-switchable`.
```

Under **Traps**, add:

```markdown
**The checklist drawer has two closes.** `closeDrawer()` is unconditional and
belongs to paths that have already saved, deleted or navigated. Escape, the
backdrop and ✕ go through `requestCloseDrawer()`, which asks first when
`drawerDirty`. Wiring a new close control to `closeDrawer()` silently brings
back the lost-note bug.
```

- [ ] **Step 10: Mark the brief done and commit**

Set `**Status:** done` in the brief and tick its Done-means boxes that were verified; leave unticked any that were not, with a one-line note why.

```bash
git add planner-web/js/pages/trip/checklist.js planner-web/trip.html planner-web/*.html CLAUDE.md .claude/briefs/2026-09-17-trip-page-design-review.md .claude/plans/2026-09-17-trip-page-design-review.md
git commit -m "Checklist drawer asks before discarding edits; document x-dialog"
```

---

## Self-review

- **Spec coverage:** #1 T1 · #2 T2 · #3 T4 · #4 T2 (row checkboxes), T4 (table checkboxes), T5 (chips, buttons, close) · #5 T5 · #6 T3 · #7 T3 · #8 T8 · #9 T1 · #10 T6 · #11 T7 · #12 dropped, reason in brief · #13 T7 · #14 T7 · #15 T1 · #16 T5 (chips, rates note), T7 (toolbar label) · dark toast T6 · CLAUDE.md T8.
- **Names used across tasks:** `x-dialog`, `data-dialog-open` (T1 → T8 checks); `.check-status` button (T2 → T8 checks); `tab-<id>`/`panel-<id>`, `revealTab`, `onTabKeydown` (T3 only); `.table-cards`, `data-label` (T4 → T8 checks); `--tp-warning-ink` (T5); `--tp-on-toast`, `.toast-close`, `.toast-region` (T6); `dateTimeLabel`, `dateLabel`, `requestStatusLabel`, `addedLabel` (T7); `drawerDirty`, `requestCloseDrawer`, `drawerDiscardAsk` (T8).
- **Merge risk:** `trip.html` regions are disjoint; T8 edits drawer lines only after T1 is merged. `_reset.scss` is T1-only; `_modal.scss` is T5-only — T1's scroll lock lives in `_reset.scss` precisely so that stays true.
