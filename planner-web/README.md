# planner-web

Static pages, no bundler. Alpine.js is vendored locally, so nothing is fetched
from a CDN at runtime. Sass is the only build step.

See the [root README](../README.md) for what the app does.

#### Install Sass
```bash
npm install
```
#### Build the stylesheets
```bash
npm run css
```
#### Rebuild them as you edit
```bash
npm run css:watch
```
#### Serve the pages
```bash
./serve.sh
```
#### Build and serve in one go
```bash
npm run dev
```

## Pointing it at the API

`js/config.js` defaults to `http://localhost:8080`. Because there is no build
step, the base URL can also be set once from the address bar and is remembered
after that:

```
http://localhost:3000/login.html?api=http://localhost:8090
```

## Pages

| Page | What it is |
|---|---|
| `login.html` | Email and password, with a show/hide eye on the password |
| `change-password.html` | The forced first sign-in — new password plus a screen name. Also reachable voluntarily |
| `account.html` | Screen name, password, and theme |
| `trips.html` | Trip cards with role, status and checklist progress; create and delete |
| `trip.html?id=…` | The workspace: Overview, Members, Destinations, Checklist, Itinerary, Budget, Publish |
| `index.html` | Redirects to the right place depending on the session |

`trip.html` is one page with hash-routed tabs (`trip.html?id=…#budget`), so a tab
can be linked to and survives a reload. It makes **one** request for the whole
trip and every tab reads from that.

## Code layout

```
js/api.js              fetch wrapper: cookies, CSRF, and the 409 password redirect
js/boot.js             page startup, and where Alpine is loaded
js/session.js          the per-page auth guards
js/chrome.js           the shared header and footer
js/format.js           dates, money, categories, currencies
js/theme-selector.js   the theme registry and switching
js/password-toggle.js  the eye control, and the strength meter
js/toast.js            confirmations and failures
js/pages/trip.js       the workspace component
js/pages/trip/*.js     one file per tab, merged into that component
```

Each tab file exports a factory returning its own state and methods. `trip.js`
merges them with `Object.defineProperties` rather than object spread — **spread
reads every property, which invokes a getter and freezes its result**, so a
computed value like `filteredChecklist` would be evaluated once before any trip
data existed and never recompute.

## Stylesheets

```
scss/abstracts/   spacing, breakpoints, mixins
scss/base/        reset, typography, forms, utilities
scss/layout/      header, page, tabs, grid
scss/components/  buttons, cards, tables, modals, drawer, toasts, …
scss/pages/       login, trips, trip
scss/themes/      one small token file per theme
scss/_core.scss   every structural rule, exactly once
scss/<theme>.scss one entry point per theme
```

Nothing in `_core.scss` names a colour or a font. It all reads CSS custom
properties (`--tp-bg`, `--tp-accent`, `--tp-font-display`, …), and a theme file
defines those under `body.theme-<name>`. That is the one deliberate difference
from the personal site, whose four theme files each carry a full copy of the
structural CSS — here a theme is about 40 lines.

Compiled output goes to `assets/css/` and is gitignored.

### Adding a theme

Four small edits, nothing else:

1. `scss/themes/_<name>.scss` — the tokens, under `body.theme-<name>`
2. `scss/<name>.scss` — `@use "themes/<name>"; @use "core";`
3. `THEME_REGISTRY` in `js/theme-selector.js`
4. A `<link id="<name>-css" disabled>` in each page's head, and the same name in
   the inline pre-paint script

Add it to `THEMES` in the API's `StaticSiteRenderer` too if published pages
should offer it.

## Things worth knowing

- **The theme is applied before first paint** by a small inline script in each
  page's head, so there is no flash of the wrong theme.
- **`<template x-for>` cannot be used inside `<svg>`.** The HTML parser hoists it
  out of the SVG namespace and Alpine then fails on `importNode`; the budget
  donut writes its arcs out explicitly for that reason.
- **A select whose options come from `x-for` must not be inside `x-if`.** The
  model is applied before the options exist, so the select silently falls back
  to its first option. The checklist drawer stays in the DOM to avoid this.
- **An `<input>` with no `type` attribute does not match `input[type="text"]`**
  in CSS. `base/_forms.scss` includes `input:not([type])` so such a field is
  never left unstyled.
- **Stylesheet links carry `?v=1`.** Bump it when you want to be sure a browser
  picks up rebuilt CSS.
