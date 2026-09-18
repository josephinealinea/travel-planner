# Panel Presentation Config Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One frontend setting switches every tab's detail view between a centred popup and a side panel.

**Architecture:** The two styles differ only in geometry — backdrop alignment, panel height, border and shadow. So this is a CSS swap driven by a body class, not two sets of templates. A marker class `panel-switchable` opts a surface in; a small module mirroring `theme-selector.js` stores the choice in localStorage and applies the body class. No markup is restructured, which is what keeps the checklist drawer out of `x-if`.

**Tech Stack:** Vanilla ES modules, Alpine.js (vendored), Dart Sass. No new dependencies.

**Spec:** `.claude/briefs/2026-09-12-panel-presentation-config.md`

## Global Constraints

- Escape-to-close and click-outside-to-close must work in **both** modes, on all four surfaces.
- The Checklist drawer's body stays permanently in the DOM. Never move it back inside `x-if` — that reintroduces the fixed bug where a `<select>` fed by `x-for` initialises before its options exist and silently shows the wrong value (CLAUDE.md, Traps).
- Frontend only. `git diff planner-api` must be empty at every commit.
- No new dependency.
- One setting governs all tabs. No per-tab override.
- **Scope decision (refines the brief):** `trip.html` has 13 modal backdrops, but only 3 are tab detail forms — Destinations, Itinerary entry, Budget expense. The other 10 are confirmations ("Delete this trip?"), the trip-edit form and the exchange-rates form. Only the 3 detail forms plus the Checklist drawer switch. A yes/no confirmation does not belong in a working side panel.
- There is no frontend test runner in this project (CLAUDE.md). Verification is browser-based with exact expected values, not a test suite. Do not add a test framework as part of this change.

---

### Task 1: Panel mode setting module

**Files:**
- Create: `planner-web/js/panel-mode.js`
- Modify: `planner-web/js/chrome.js`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `PANEL_MODES` (object keyed `popup` | `side`, each `{label: string}`), `DEFAULT_PANEL_MODE` (string `'popup'`), `savedPanelMode(): string`, `applyPanelMode(mode: string): string` (returns the mode actually applied), and a `panelmodechange` CustomEvent with `detail.mode`. Tasks 2 and 3 rely on the body classes `panels-popup` / `panels-side`.

- [ ] **Step 1: Create the setting module**

Create `planner-web/js/panel-mode.js`:

```js
/**
 * How a tab's detail view opens: a centred popup, or a side panel.
 *
 * Both styles are wanted while the choice is still open, so this is a setting
 * rather than a rewrite. Only geometry moves — the markup, the Alpine state and
 * the close handlers are identical in both modes.
 */
export const PANEL_MODES = {
  popup: { label: 'Popup — opens centred' },
  side: { label: 'Side panel — opens at the right' },
};

export const DEFAULT_PANEL_MODE = 'popup';
const STORAGE_KEY = 'panelMode';

export function savedPanelMode() {
  try {
    const saved = localStorage.getItem(STORAGE_KEY);
    return PANEL_MODES[saved] ? saved : DEFAULT_PANEL_MODE;
  } catch {
    // Private browsing can throw on access, not just return null.
    return DEFAULT_PANEL_MODE;
  }
}

export function applyPanelMode(mode) {
  const next = PANEL_MODES[mode] ? mode : DEFAULT_PANEL_MODE;

  document.body.classList.remove(
    ...Object.keys(PANEL_MODES).map((name) => `panels-${name}`));
  document.body.classList.add(`panels-${next}`);

  try { localStorage.setItem(STORAGE_KEY, next); } catch { /* private mode */ }

  document.dispatchEvent(new CustomEvent('panelmodechange', { detail: { mode: next } }));
  return next;
}
```

- [ ] **Step 2: Apply it on every page load**

In `planner-web/js/chrome.js`, extend the existing theme import:

```js
import { renderSelector, initThemeSelector, applyTheme, savedTheme } from './theme-selector.js';
import { applyPanelMode, savedPanelMode } from './panel-mode.js';
import { initPasswordToggles } from './password-toggle.js';
import { signOut } from './session.js';
```

Then, in `renderChrome()`, immediately before the closing `initPasswordToggles();` line, add:

```js
  // Panels are hidden at load, so unlike the theme this needs no pre-paint
  // script — there is nothing visible to flash.
  applyPanelMode(savedPanelMode());
```

- [ ] **Step 3: Verify the body class is applied and persists**

Start the servers if they are not running:

```bash
cd planner-web && ./serve.sh
```

Open `http://localhost:3000/trips.html`, sign in, then in the browser console:

```js
document.body.className
```
Expected: includes `panels-popup` (the default).

Then:
```js
localStorage.setItem('panelMode', 'side'); location.reload();
```
After reload, `document.body.className` — Expected: includes `panels-side`, not `panels-popup`.

Then check the invalid-value fallback:
```js
localStorage.setItem('panelMode', 'nonsense'); location.reload();
document.body.className
```
Expected: includes `panels-popup`. An unrecognised stored value must fall back, not apply a `panels-nonsense` class.

- [ ] **Step 4: Commit**

```bash
git add planner-web/js/panel-mode.js planner-web/js/chrome.js
git commit -m "Add panel mode setting, applied as a body class"
```

---

### Task 2: Geometry swap and marker classes

**Files:**
- Create: `planner-web/scss/components/_panel-mode.scss`
- Modify: `planner-web/scss/_core.scss`
- Modify: `planner-web/trip.html` (4 attributes)

**Interfaces:**
- Consumes: body classes `panels-popup` / `panels-side` from Task 1.
- Produces: the marker class `panel-switchable`. Any future detail surface must carry it to participate; without it a surface stays centred in both modes.

- [ ] **Step 1: Create the override stylesheet**

Create `planner-web/scss/components/_panel-mode.scss`:

```scss
@use "../abstracts/tokens" as t;
@use "../abstracts/mixins" as m;
@use "../abstracts/breakpoints" as bp;

// One setting swaps how a tab's detail view opens: centred popup, or side
// panel. Only geometry moves — the markup and the Alpine state are identical in
// both modes, which is why this is a stylesheet and not two sets of templates.
//
// Applies ONLY to surfaces marked .panel-switchable. Confirmation dialogs
// ("Delete this trip?") stay centred in both modes: a yes/no question does not
// belong in a working side panel.
//
// Below the mobile breakpoint both styles already collapse to the same bottom
// sheet, so the setting only needs to do anything on wider screens. Scoping the
// whole file to a min-width avoids fighting the mobile rules in _modal.scss and
// _drawer.scss.
@include m.from(bp.$bp-md) {

  // Popup mode: the checklist detail, normally a side drawer, becomes centred.
  body.panels-popup {
    .drawer-backdrop.panel-switchable {
      align-items: center;
      justify-content: center;
      padding: t.$space-4;
      overflow-y: auto;
    }

    .drawer-backdrop.panel-switchable > .drawer {
      height: auto;
      max-height: calc(100vh - #{t.$space-6});
      border: 1px solid var(--tp-border);
      border-radius: var(--tp-radius);
      box-shadow: 0 12px 40px rgba(0, 0, 0, 0.22);
    }
  }

  // Side mode: the three detail forms, normally centred, become side panels.
  body.panels-side {
    .modal-backdrop.panel-switchable {
      align-items: stretch;
      justify-content: flex-end;
      padding: 0;
    }

    .modal-backdrop.panel-switchable > .modal {
      max-width: 520px;
      max-height: none;
      height: 100%;
      border: 0;
      border-left: 1px solid var(--tp-border);
      border-radius: 0;
      box-shadow: -8px 0 30px rgba(0, 0, 0, 0.18);
    }
  }
}
```

- [ ] **Step 2: Register it in the core stylesheet**

In `planner-web/scss/_core.scss`, add the new partial immediately after the drawer line so the override loads after both files it overrides:

```scss
@use "components/modal";
@use "components/drawer";
@use "components/panel-mode";
@use "components/toast";
```

- [ ] **Step 3: Mark the four switchable surfaces**

In `planner-web/trip.html`, add `panel-switchable` to exactly four backdrops. Anchor on the `x-show` attribute, which is unique per surface — line numbers shift as you edit.

```bash
cd planner-web
# Checklist detail (currently a drawer)
sed -i '' 's|<div class="drawer-backdrop" x-show="openItem"|<div class="drawer-backdrop panel-switchable" x-show="openItem"|' trip.html
# Destinations form
sed -i '' 's|<div class="modal-backdrop" x-show="destFormOpen"|<div class="modal-backdrop panel-switchable" x-show="destFormOpen"|' trip.html
# Itinerary entry form
sed -i '' 's|<div class="modal-backdrop" x-show="entryOpen"|<div class="modal-backdrop panel-switchable" x-show="entryOpen"|' trip.html
# Budget expense form
sed -i '' 's|<div class="modal-backdrop" x-show="expenseOpen"|<div class="modal-backdrop panel-switchable" x-show="expenseOpen"|' trip.html
```

Verify exactly four, and that no confirmation dialog was caught:

```bash
grep -c 'panel-switchable' trip.html
```
Expected: `4`

```bash
grep -n 'panel-switchable' trip.html
```
Expected: the four lines carry `x-show="openItem"`, `x-show="destFormOpen"`, `x-show="entryOpen"`, `x-show="expenseOpen"` — and nothing else.

- [ ] **Step 4: Rebuild the stylesheets**

```bash
cd planner-web && npm run css
```
Expected: exits 0, no Sass warnings.

```bash
grep -c 'panel-switchable' assets/css/minima.css
```
Expected: a non-zero count — the rules reached the compiled output.

- [ ] **Step 5: Verify the geometry actually swaps**

Open a trip, go to the Destinations tab, click **+ Add destination**, then in the console:

```js
const b = document.querySelector('.modal-backdrop.panel-switchable');
const s = getComputedStyle(b);
({ justify: s.justifyContent, align: s.alignItems, pad: s.padding });
```
Expected in popup mode: `{justify: "center", align: "center", pad: "16px"}`.

Switch and reload:
```js
localStorage.setItem('panelMode', 'side'); location.reload();
```
Reopen the same form and run the snippet again.
Expected in side mode: `{justify: "flex-end", align: "stretch", pad: "0px"}`.

Now confirm a **confirmation dialog did not move**. Still in side mode, open the Destinations tab, click **Delete** on a destination, then:

```js
const c = document.querySelector('.modal-backdrop:not(.panel-switchable)');
getComputedStyle(c).justifyContent
```
Expected: `center` — confirmations stay centred in both modes.

- [ ] **Step 6: Verify the checklist detail goes the other way**

In popup mode (`localStorage.setItem('panelMode', 'popup'); location.reload();`), open the Checklist tab and click any item, then:

```js
const d = document.querySelector('.drawer-backdrop.panel-switchable');
getComputedStyle(d).justifyContent
```
Expected: `center`. In side mode the same snippet returns `flex-end`.

- [ ] **Step 7: Verify the x-if trap has not returned**

This is the constraint most likely to be broken silently. With the Checklist detail open on an item that has both a category and a destination:

```js
({ cat: document.querySelector('#drawer-category').value,
   dest: document.querySelector('#drawer-destination').selectedOptions[0].textContent.trim() })
```
Expected in **both** modes: the item's real category and destination — e.g. `{cat: "LODGING", dest: "Cusco"}`. If either shows the first option instead (`TRANSPORTATION`, `— none —`), the drawer body has been moved inside `x-if` and the fix must be reverted.

- [ ] **Step 8: Verify close behaviour in both modes**

For each of the four surfaces, in each mode: open it, press `Escape` (expect it closes), reopen, click the backdrop outside the panel (expect it closes). Eight surface/mode combinations, two gestures each.

- [ ] **Step 9: Confirm no backend change**

```bash
git diff planner-api
```
Expected: empty output.

- [ ] **Step 10: Commit**

```bash
git add planner-web/scss/components/_panel-mode.scss planner-web/scss/_core.scss planner-web/trip.html
git commit -m "Switch detail panels between popup and side by body class"
```

---

### Task 3: Setting UI in Account, and docs

**Files:**
- Modify: `planner-web/account.html`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: `PANEL_MODES`, `savedPanelMode`, `applyPanelMode` from Task 1; the `panelmodechange` event.
- Produces: nothing later tasks depend on.

- [ ] **Step 1: Add the control to the Appearance card**

In `planner-web/account.html`, the Appearance section currently holds only the theme radios. Replace that section's body so it carries both settings:

```html
    <section class="card">
      <div class="card-header"><h2 class="card-title">Appearance</h2></div>
      <p class="small muted">These are remembered in this browser.</p>

      <div class="field">
        <span class="label">Theme</span>
        <div id="theme-radios" class="stack-sm"></div>
      </div>

      <div class="field">
        <span class="label">Detail views open as</span>
        <div id="panel-radios" class="stack-sm"></div>
      </div>
    </section>
```

- [ ] **Step 2: Render and wire the radios**

In the same file's module script, add to the imports:

```js
  import { PANEL_MODES, savedPanelMode, applyPanelMode } from './js/panel-mode.js';
```

Then append at the end of the script, after the existing `themechange` listener:

```js
  // ── detail panels ─────────────────────────────────
  const panelRadios = document.querySelector('#panel-radios');
  panelRadios.innerHTML = Object.entries(PANEL_MODES).map(([mode, config]) => `
    <label class="checkbox">
      <input type="radio" name="panelMode" value="${mode}" ${mode === savedPanelMode() ? 'checked' : ''}>
      ${escapeHtml(config.label)}
    </label>`).join('');

  panelRadios.addEventListener('change', (event) => {
    if (event.target.name === 'panelMode') applyPanelMode(event.target.value);
  });
```

`escapeHtml` is already imported in this file at `account.html:99` (`import { renderChrome, escapeHtml } from './js/chrome.js';`), so it needs no new import.

- [ ] **Step 3: Verify the control works end to end**

Open `http://localhost:3000/account.html`.

Expected: an **Appearance** card with a Theme group and a "Detail views open as" group offering "Popup — opens centred" and "Side panel — opens at the right", with the current mode pre-selected.

Select the other option, then:
```js
localStorage.getItem('panelMode')
```
Expected: the value you just picked.

Navigate to a trip, open the Destinations form, and confirm it opens in the style you chose. Reload and confirm the choice survived.

- [ ] **Step 4: Document the setting and the marker-class rule**

In `CLAUDE.md`, in the **Frontend architecture** section, after the paragraph beginning "**Stylesheets are token-based.**", insert:

```markdown
**Detail views are popup or side panel, by setting.** `js/panel-mode.js` stores
the choice and puts `panels-popup` or `panels-side` on the body;
`scss/components/_panel-mode.scss` swaps the geometry. Only surfaces marked
`panel-switchable` participate — the four tab detail views. Confirmation dialogs
stay centred in both modes on purpose. **A new detail surface must carry
`panel-switchable`** or it will ignore the setting.
```

- [ ] **Step 5: Confirm no backend change and commit**

```bash
git diff planner-api
```
Expected: empty output.

```bash
git add planner-web/account.html CLAUDE.md
git commit -m "Add panel mode control to Account, document the setting"
```

---

## Verification against the brief

Run these once all three tasks are done. They are the brief's Done means, verbatim.

- [ ] Set to popup: Destinations, Itinerary, Budget and Checklist all open centred
- [ ] Set to panel: all four open as a side panel
- [ ] Escape and click-outside close the panel in both modes, on all four tabs
- [ ] The Checklist detail still shows the correct Category and Destination in both modes when opened on an existing item
- [ ] The choice survives a reload
- [ ] `git diff planner-api` is empty
