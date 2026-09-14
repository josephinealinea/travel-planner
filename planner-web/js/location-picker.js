import { toggleId } from './selection.js';

/**
 * Shared "Use in" / "Location" country picker.
 *
 * The checklist, itinerary and expense forms each let a member attach zero or
 * more countries to a record — "Use in (optional)" on the checklist form,
 * "Location (optional)" on the other two. All three should look and behave
 * identically, so the toggle and display logic lives here once instead of
 * being reinvented per form.
 *
 * These used to attach destinations. A country is what every consumer of the
 * link actually wanted — a chip, a filter, a group-by, the budget's country
 * breakdown — and picking one city out of three in the same country was a
 * distinction none of them kept. The choices are still derived from the trip's
 * destinations, so nothing can be filed under a country the trip does not
 * visit.
 *
 * There is no templating system for static HTML pages in this project, so the
 * markup itself is copied per form rather than shared — but it is exactly
 * this block, reusing the chip-group/chip classes already styled for the
 * checklist tab's category filter (no new Sass needed):
 *
 *   <div class="field">
 *     <label id="my-field-label">Use in (optional)</label>
 *     <div class="chip-group" role="group" aria-labelledby="my-field-label">
 *       <template x-for="country in tripCountries" :key="country.code">
 *         <button type="button" class="chip"
 *                 :aria-pressed="form.countryCodes.includes(country.code)"
 *                 @click="toggleLocation(form.countryCodes, country.code)"
 *                 x-text="country.label"></button>
 *       </template>
 *       <span class="tiny muted" x-show="!tripCountries.length">No countries yet</span>
 *     </div>
 *   </div>
 *
 * `form.countryCodes` is a plain array of two-letter codes on whatever form
 * object the field belongs to (the add form, the drawer's edit form, ...).
 * Keeping it inside the surface's own object — not inside an `x-if` — is what
 * keeps this safe: see the CLAUDE.md trap about a `<select>` (or, as here, a
 * `<template x-for>`) whose options depend on data that might not exist yet.
 */

/** Adds or removes a country code, mutating the array in place. */
export function toggleLocation(countryCodes, countryCode) {
  toggleId(countryCodes, countryCode);
}

/**
 * The countries this trip visits, once each, in destination order.
 *
 * Derived rather than stored, so adding or removing a destination changes what
 * the pickers offer with nothing to keep in step. A destination with no country
 * contributes nothing — some places have no gazetteer entry at all.
 */
export function countriesOfTrip(allDestinations) {
  const seen = new Map();
  for (const destination of allDestinations || []) {
    const code = (destination.countryCode || '').trim().toUpperCase();
    if (!code || seen.has(code)) continue;
    const name = destination.countryName || code;
    seen.set(code, {
      code,
      name,
      flag: destination.countryFlag || '',
      label: destination.countryFlag ? `${destination.countryFlag} ${name}` : name,
    });
  }
  return [...seen.values()];
}

/** Labels for every code that the trip still visits, in trip order. */
export function locationNames(allDestinations, countryCodes) {
  if (!countryCodes || !countryCodes.length) return [];
  return countriesOfTrip(allDestinations)
    .filter((country) => countryCodes.includes(country.code))
    .map((country) => country.label);
}
