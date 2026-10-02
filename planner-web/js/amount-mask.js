/**
 * Whether the member has hidden amounts on the trip pages. Only meaningful
 * while the account setting "Show amount masking" is on; the setting decides
 * if the toggle exists, this remembers which way it was left. A per-browser
 * view state like the Mine / Whole trip switch, so it lives in localStorage
 * and defaults to amounts shown.
 */
const KEY = 'amountsMasked';

export function savedAmountsMasked() {
  try {
    return localStorage.getItem(KEY) === 'true';
  } catch {
    return false;
  }
}

export function saveAmountsMasked(value) {
  try { localStorage.setItem(KEY, String(!!value)); } catch { /* private mode: lasts for this page only */ }
}
