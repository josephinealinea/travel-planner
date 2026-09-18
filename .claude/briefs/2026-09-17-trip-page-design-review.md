# Trip page: design review fixes

**Status:** done — uncommitted, awaiting the user's commit
**Date:** 2026-09-17
**Source:** design review of `trip.html` (LATAM Trip 2026, Dark theme, 1440px and
500px), run against the design-guideline references in the apple-design-skill.
**Plan:** `.claude/plans/2026-09-17-trip-page-design-review.md`

## Job
Fix the accessibility, touch and phone-layout problems the review found on the
trip page, plus the small consistency items, without changing what any tab does.

## Why
The page is visually consistent and its wording is honest, but a keyboard or
screen-reader user cannot work it (focus stays behind every dialog, the
checklist tick is a `<span>`), a phone user cannot see the money column of their
own budget, and Minima's "Pending" — the one state the budget most needs noticed —
is 2.89:1.

## Findings being fixed

| # | Finding | Measured |
|---|---|---|
| 1 | Opening a modal or the checklist drawer leaves focus on the trigger behind it; Tab walks the page underneath; the page behind still scrolls | Tab from the drawer landed on an `INPUT` outside it |
| 2 | `.check-status` tick is a `<span>` with `tabIndex -1` | 22×23px, not focusable, no name |
| 3 | Budget table on a phone: Amount, Shared Amount, Status, Edit off-screen, no scroll cue | 1033px of table in a 474px box |
| 4 | Touch targets | chips 22px tall, `.btn-sm` 32px, row checkboxes 13×13, `.modal-close` ~20px |
| 5 | Minima contrast | `--tp-warning` #ca8a04 as text 2.89:1 / 2.54:1; `--tp-success` on `--tp-surface-alt` 4.32:1 |
| 6 | Tabs: no `aria-controls`, no tabpanels, no arrow keys | — |
| 7 | Phone tab bar is cut off after Checklist with no cue | 794px of tabs in 476px |
| 8 | Escape / backdrop / ✕ on the checklist drawer silently discards unsaved edits | `closeDrawer()` never checks |
| 9 | Dialogs with no accessible name | Remove member, Publish, Unpublish |
| 10 | Error toasts vanish after 4s in a polite live region | `LIFETIME_MS = 4000` for every variant |
| 11 | Category / Status / Show chip groups have no group label | only Group by and Budget Show do |
| 13 | Three date formats; seconds on publish times; raw enum `APPROVED` | `24 Oct`, `14/09/2026`, `17/09/2026, 07:43:30` |
| 14 | "Open my page" butts against the button row below it | no margin between two `.btn-row`s |
| 15 | `scroll-behavior: smooth` ignores reduced motion | — |
| 16 | 12px chips and toolbar labels, ~11.5px rates note | `0.75rem`, `0.72rem` |
| — | Dark theme `toast.show()` text 1.28:1 (latent — no caller today) | `--tp-on-accent` on `--tp-toast-bg` |

**Dropped from the review:** #12 ("hide Delete selected until something is
selected"). On reflection it is worse than what is there: the toolbar row would
appear under the pointer on the first tick and shove every row down mid-selection.
A disabled control that stays put is the right call.

## Guardrails
- Frontend only. `git diff planner-api` stays empty.
- No new dependency. The Alpine Focus plugin would do #1 but is another vendored
  script; a ~70-line directive does the same job.
- Must not change: the checklist drawer body stays in the DOM, not in `x-if`
  (CLAUDE.md, Traps — the `<select>`/`x-for` bug).
- Must not change: Escape and click-outside still close every surface, in both
  panel modes.
- Must not change: tabs still route through the hash with `replaceState`.
- Colours only through tokens. A theme that does not define a new token must
  look exactly as it does today (the `var(--new, var(--old))` fallback idiom).
- Structural CSS selectors must not count children in `x-for` output
  (CLAUDE.md, Traps — the leftover `<template>`).
- Out of scope: other pages (`trips.html`, `account.html`) — the dialog
  directive will be available to them, but wiring it there is a follow-up; the
  filters-in-a-sheet idea for phones (needs a design decision); card layout for
  the Destinations and Members tables.

## Done means
Verified 2026-09-17 in Chrome, signed in, LATAM Trip 2026, Dark theme unless noted.

- [x] Opening a dialog moves focus inside it; Tab and Shift+Tab stay inside; closing returns focus to its opener.
      Focus-in and focus-return confirmed on the drawer, Mark complete, Edit trip, Add destination, Add expense
      and Unpublish. Add checklist item, Add itinerary item and Remove member confirmed for name, Escape and
      focus return. The three bulk deletes were not opened; they use the same directive.
- [x] The page behind an open dialog does not scroll (`body` overflow `hidden` while open, restored on close)
- [x] Every `role="dialog"` has an accessible name (15 of 15)
- [x] The checklist tick is a button reachable by Tab, named "Complete: …", 32×44 desktop / 44×44 at 400px.
      Not yet listened to with VoiceOver.
- [x] Left/Right/Home/End move between tabs, End→Right wraps to Overview, Tab leaves the list for the panel;
      7 tabpanels
- [x] At 400px the selected tab is scrolled into view and the bar fades at the right edge
- [x] At 400px every budget row shows description, amount, share, status, date, source and Edit (table 374/374)
- [x] Minima Pending chip 5.13:1, Completed badge 5.55:1 (measured from rendered colours)
- [x] At 400px chips ≥36px, `.btn-sm` ≥40px. `.modal-close` 44px rule built but not measured open at 400px.
- [x] Error toast still showing after 4.6s in the `role="alert"` region; ✕ dismisses it; success toast faded
- [x] Closing the drawer with unsaved edits asks first; the prompt takes focus and scrolls into view;
      Keep editing keeps the text; Escape twice discards
- [x] Publish/request dates read `15 Sep 2026, 01:25`; request status reads `Approved` / `Rejected`
- [x] `npm run css` builds; every tab loaded and was used without a broken state
- [x] CLAUDE.md documents `x-dialog`, the tabs pattern, the two drawer closes, and the background-tab trap

## Found during verification, not fixed
- ~~**The site header overflows at 400px** by 55px — the nav plus the theme toggle (`chrome.js`, shared by
  every page). The page scrolls sideways at that width.~~ **Fixed 2026-09-18.** Brand (166px) plus nav
  (269px) plus padding needs ~467px, so below that the header pushed the page sideways. The wordmark is the
  one part that can give way: `.site-brand-name` (a new class on the span `chrome.js` already rendered) is
  `visually-hidden` below `$bp-sm`, leaving the compass. It is hidden rather than `display: none` because the
  compass is `aria-hidden`, so removing it would leave the brand link with no accessible name — the a11y tree
  still reads `link "Travel Planner"`. Verified: no horizontal page scroll at 360/400/480/599/600/768/1024/1440,
  wordmark hidden below 600 and visible from 600 up, and all 11 pages clean at 400px.
- Legacy data: "Shopping: Baby alpaca items" predates `allDay` and stores a midnight time, so it still reads
  `00:00 – 00:00`. Re-saving it without a time fixes it.
