/**
 * Where the API lives.
 *
 * By default, the page's own origin: deployed, the pages and the API share one
 * origin, with Cloudflare Pages proxying /api/* to Cloud Run (docs/deploy.md).
 * That is what lets the auth cookies stay host-only and SameSite=Lax. The one
 * exception is serve.sh on :3000, which only serves files, so local
 * development keeps talking to bootRun on :8080 exactly as before.
 *
 * There is no build step, so rather than editing this file per environment the
 * base URL can also be set once with ?api=http://host:port — it is remembered
 * from then on. Handy for pointing these static pages at a deployed API, or at
 * a local one running on a different port.
 */
const LOCAL_DEV_API_BASE = 'http://localhost:8080';

function defaultApiBase() {
  const { hostname, port, origin } = window.location;
  const local = hostname === 'localhost' || hostname === '127.0.0.1';
  return local && port === '3000' ? LOCAL_DEV_API_BASE : origin;
}

const DEFAULT_API_BASE = defaultApiBase();
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

/** Budget table rows per page until a member picks their own in Account → Appearance. */
export const DEFAULT_BUDGET_PAGE_SIZE =
  globalOverride('PLANNER_BUDGET_PAGE_SIZE', [10, 20, 50, 100], 20);
