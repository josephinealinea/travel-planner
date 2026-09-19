/**
 * Mine or Whole trip: which part of a trip the planner shows. Remembered in
 * this browser, like the panel mode beside it, and never sent anywhere — it is
 * a way of looking, not a setting of the trip. Defaults to Mine.
 */
const KEY = 'tripScope';

export function savedScope() {
  try {
    return localStorage.getItem(KEY) === 'trip' ? 'trip' : 'mine';
  } catch {
    return 'mine';
  }
}

export function saveScope(scope) {
  try {
    localStorage.setItem(KEY, scope === 'trip' ? 'trip' : 'mine');
  } catch { /* private mode: the choice lasts for this page only */ }
}
