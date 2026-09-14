/**
 * Where the API lives.
 *
 * There is no build step, so rather than editing this file per environment the
 * base URL can also be set once with ?api=http://host:port — it is remembered
 * from then on. Handy for pointing these static pages at a deployed API, or at
 * a local one running on a different port.
 */
const DEFAULT_API_BASE = 'http://localhost:8080';
const STORAGE_KEY = 'plannerApiBase';

function fromQuery() {
  try {
    const value = new URL(window.location.href).searchParams.get('api');
    if (!value) return null;
    // Only remember something that parses as an http(s) origin.
    const url = new URL(value);
    if (url.protocol !== 'http:' && url.protocol !== 'https:') return null;
    return value.replace(/\/$/, '');
  } catch {
    return null;
  }
}

function resolve() {
  if (window.PLANNER_API_BASE) return window.PLANNER_API_BASE.replace(/\/$/, '');

  const queryValue = fromQuery();
  if (queryValue) {
    try { localStorage.setItem(STORAGE_KEY, queryValue); } catch { /* private mode */ }
    return queryValue;
  }

  try {
    return localStorage.getItem(STORAGE_KEY) || DEFAULT_API_BASE;
  } catch {
    return DEFAULT_API_BASE;
  }
}

export const API_BASE = resolve();

/**
 * Settings that are not worth a round trip to the API.
 *
 * There is no build step here, so there is no bundler to inject environment
 * values at compile time. The convention instead — the same one API_BASE
 * above uses — is: a default in this file, overridable per deployment by
 * setting a global in the page's <head> before the modules load:
 *
 *   <script>window.PLANNER_PANEL_MODE = 'side';</script>
 *
 * That inline script is this project's equivalent of the API's
 * application.yml: one place per deployment, no rebuild, no editing JS.
 * Anything both halves must agree on — the currency catalogue, for one —
 * belongs in application.yml and is served from /api/v1/config instead, so
 * the two can never drift.
 */
function globalOverride(name, allowed, fallback) {
  const value = window[name];
  return allowed.includes(value) ? value : fallback;
}

/** How a tab's detail view opens: 'popup' (centred) or 'side' (right-hand panel). */
export const DEFAULT_PANEL_MODE =
  globalOverride('PLANNER_PANEL_MODE', ['popup', 'side'], 'popup');
