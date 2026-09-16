import { API_BASE } from './config.js';

/**
 * Thin fetch wrapper around the planner API.
 *
 *  - always sends cookies, because the session lives in an httpOnly cookie and
 *    is never readable from script;
 *  - echoes the CSRF cookie back as a header on state-changing calls;
 *  - turns the API's 409 password_change_required into a redirect, so an
 *    invited member who has not chosen a password cannot get stuck on a page
 *    where nothing works.
 */

function readCookie(name) {
  const match = document.cookie.match(new RegExp('(^|;\\s*)' + name + '=([^;]*)'));
  return match ? decodeURIComponent(match[2]) : null;
}

export class ApiError extends Error {
  constructor(status, body) {
    super(body?.detail || body?.title || `Request failed (${status})`);
    this.status = status;
    this.code = body?.code || null;
    this.body = body;
    this.fieldErrors = body?.errors || null;
  }

  /** The message plus any per-field detail, ready to show in an alert. */
  get fullMessage() {
    if (!this.fieldErrors) return this.message;
    return this.message + ' — ' + Object.values(this.fieldErrors).join('; ');
  }
}

function onChangePasswordPage() {
  return location.pathname.endsWith('/change-password.html');
}

async function request(method, path, body) {
  const headers = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';

  if (method !== 'GET') {
    const csrf = readCookie('XSRF-TOKEN');
    if (csrf) headers['X-XSRF-TOKEN'] = csrf;
  }

  let response;
  try {
    response = await fetch(API_BASE + path, {
      method,
      headers,
      credentials: 'include',
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, {
      detail: `Could not reach the API at ${API_BASE}. Check it is running, `
        + 'or point this page somewhere else by adding ?api=http://host:port to the URL.',
    });
  }

  if (response.status === 204) return null;

  const text = await response.text();
  let payload = null;
  if (text) {
    try { payload = JSON.parse(text); } catch { payload = { detail: text }; }
  }

  if (!response.ok) {
    const error = new ApiError(response.status, payload);
    // The API refuses everything else until a new password is chosen.
    if (error.code === 'password_change_required' && !onChangePasswordPage()) {
      location.href = 'change-password.html';
    }
    throw error;
  }
  return payload;
}

const get = (path) => request('GET', path);
const post = (path, body) => request('POST', path, body ?? {});
const patch = (path, body) => request('PATCH', path, body ?? {});
const del = (path) => request('DELETE', path);

const trip = (id) => `/api/v1/trips/${encodeURIComponent(id)}`;

export const api = {
  get, post, patch, delete: del,

  /** Primes the XSRF-TOKEN cookie. Call once per page load, before anything else. */
  async initCsrf() {
    try { await get('/api/v1/auth/csrf'); } catch { /* non-fatal */ }
  },

  // ── auth and account ───────────────────────────────
  login: (data) => post('/api/v1/auth/login', data),
  logout: () => post('/api/v1/auth/logout'),
  me: () => get('/api/v1/auth/me'),
  config: () => get('/api/v1/config'),
  updateProfile: (data) => patch('/api/v1/account/profile', data),
  updateCurrencies: (data) => patch('/api/v1/account/currencies', data),
  updateDisplayCurrency: (data) => patch('/api/v1/account/display-currency', data),
  changePassword: (data) => post('/api/v1/account/password', data),
  // Only the flags being changed need sending; the rest are left as they are.
  updatePublishedPage: (settings) => patch('/api/v1/account/published-page', settings),

  // ── trips ──────────────────────────────────────────
  trips: () => get('/api/v1/trips'),
  trip: (id) => get(trip(id)),
  createTrip: (data) => post('/api/v1/trips', data),
  updateTrip: (id, data) => patch(trip(id), data),
  deleteTrip: (id) => del(trip(id)),

  // ── members ────────────────────────────────────────
  members: (id) => get(`${trip(id)}/members`),
  addMember: (id, email) => post(`${trip(id)}/members`, { email }),
  removeMember: (id, userId) => del(`${trip(id)}/members/${encodeURIComponent(userId)}`),

  // ── destinations ───────────────────────────────────
  geocode: (query) => get(`/api/v1/geocode?q=${encodeURIComponent(query)}`),
  countries: () => get('/api/v1/geocode/countries'),
  destinations: (id) => get(`${trip(id)}/destinations`),
  addDestination: (id, data) => post(`${trip(id)}/destinations`, data),
  updateDestination: (id, destId, data) => patch(`${trip(id)}/destinations/${destId}`, data),
  deleteDestination: (id, destId) => del(`${trip(id)}/destinations/${destId}`),
  reorderDestinations: (id, orderedIds) => post(`${trip(id)}/destinations/reorder`, { orderedIds }),

  // ── checklist ──────────────────────────────────────
  checklist: (id) => get(`${trip(id)}/checklist`),
  addCheck: (id, data) => post(`${trip(id)}/checklist`, data),
  updateCheck: (id, itemId, data) => patch(`${trip(id)}/checklist/${itemId}`, data),
  setCheckStatus: (id, itemId, status) => patch(`${trip(id)}/checklist/${itemId}/status`, { status }),
  deleteCheck: (id, itemId) => del(`${trip(id)}/checklist/${itemId}`),
  planTemplate: (id, itemId) => get(`${trip(id)}/checklist/${itemId}/plan-template`),

  // ── itinerary ──────────────────────────────────────
  itinerary: (id) => get(`${trip(id)}/itinerary`),
  addPlan: (id, data) => post(`${trip(id)}/itinerary`, data),
  updatePlan: (id, planId, data) => patch(`${trip(id)}/itinerary/${planId}`, data),
  // One day of a plan.
  deleteEntry: (id, itemId) => del(`${trip(id)}/itinerary/${itemId}`),
  // The plan and every day it covers.
  deletePlan: (id, itemId) => del(`${trip(id)}/itinerary/${itemId}/plan`),

  // Where the trip is on each of its days, with that day's weather. Its own
  // request on purpose: it calls Open-Meteo, and the trip bundle must not wait
  // on somebody else's server — see WeatherController.
  weather: (id) => get(`${trip(id)}/weather`),

  // ── budget ─────────────────────────────────────────
  budget: (id) => get(`${trip(id)}/budget`),
  addExpense: (id, data) => post(`${trip(id)}/budget`, data),
  updateExpense: (id, itemId, data) => patch(`${trip(id)}/budget/${itemId}`, data),
  deleteExpense: (id, itemId) => del(`${trip(id)}/budget/${itemId}`),

  // ── publish ────────────────────────────────────────
  publish: (id, theme) => post(`${trip(id)}/publish`, { theme }),
  unpublish: (id) => del(`${trip(id)}/publish`),
  // The theme rides along: the page is built when the request is sent, in the
  // requesting member's own theme, which only their browser knows.
  requestPublish: (id, note, theme) => post(`${trip(id)}/publish-requests`, { note, theme }),
  // Members only. The page a pending request built, before anyone approves it.
  publishPreviewUrl: (id) => `${API_BASE}${trip(id)}/publish/preview`,
  publishRequests: (id) => get(`${trip(id)}/publish-requests`),
  cancelPublishRequest: (id, reqId) => post(`${trip(id)}/publish-requests/${reqId}/cancel`),
  approvePublish: (id, reqId, theme) => post(`${trip(id)}/publish-requests/${reqId}/approve`, { theme }),
  rejectPublish: (id, reqId) => post(`${trip(id)}/publish-requests/${reqId}/reject`),
};
