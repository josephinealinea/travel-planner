/**
 * The Country half of the Filter row on the Checklist, Itinerary and Expenses.
 *
 * Countries are ticked like categories (any of them), and the two kinds combine
 * the way two kinds should: a record must match a ticked category AND touch a
 * ticked country, where either kind is ignored while nothing of it is ticked.
 * A record linked to several countries touches each of them; one linked to none
 * touches none, so it drops out while a country is ticked.
 */

/** The ticked codes the trip still visits; a country dropped since is ignored. */
export function activeCountries(ticked, tripCountries) {
  const visited = (tripCountries || []).map((country) => country.code);
  return (ticked || []).filter((code) => visited.includes(code));
}

/** True when no country is ticked, or the record touches one that is. */
export function touchesCountries(countryCodes, active) {
  return !active.length || (countryCodes || []).some((code) => active.includes(code));
}

/** Tick or untick one code, in place (the filter lives on the Alpine state). */
export function toggleCountry(ticked, code) {
  const index = ticked.indexOf(code);
  if (index >= 0) ticked.splice(index, 1);
  else ticked.push(code);
}
