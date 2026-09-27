/**
 * Whether Llama Lookout (the news carousel on the Destinations tab) is shown
 * at all.
 *
 * A per-browser preference like the theme and the page size, not an account
 * setting: it lives in localStorage and is read once when the trip page
 * loads. Off means loadNews() never calls the API and the panel never
 * renders - not just hidden with CSS, since the point is to skip the call.
 * Defaults to off: a member opts in rather than a fresh account silently
 * spending an external API's quota nobody asked for.
 */
const STORAGE_KEY = 'showNews';

export function savedShowNews() {
  try {
    return localStorage.getItem(STORAGE_KEY) === 'true';
  } catch {
    // Private browsing can throw on access, not just return null.
    return false;
  }
}

export function saveShowNews(value) {
  const next = !!value;
  try { localStorage.setItem(STORAGE_KEY, String(next)); } catch { /* private mode */ }
  return next;
}
