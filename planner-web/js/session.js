import { api } from './api.js';

/**
 * Page-level auth guards. Each page states what it needs and gets redirected if
 * that is not the case, so no page has to reason about the possibilities.
 */

let cached = null;

export async function currentUser({ refresh = false } = {}) {
  if (cached && !refresh) return cached;
  try {
    cached = await api.me();
  } catch {
    cached = null;
  }
  return cached;
}

/** For pages that need a signed-in user who has already chosen a password. */
export async function requireUser() {
  const user = await currentUser();
  if (!user) {
    location.href = `login.html?next=${encodeURIComponent(location.pathname + location.search)}`;
    return null;
  }
  if (user.mustChangePassword && !location.pathname.endsWith('/change-password.html')) {
    location.href = 'change-password.html';
    return null;
  }
  return user;
}

/** For login: bounce straight through if there is already a session. */
export async function redirectIfSignedIn() {
  const user = await currentUser();
  if (!user) return false;
  location.href = user.mustChangePassword ? 'change-password.html' : afterLoginTarget();
  return true;
}

/** Honours ?next=, but only for same-origin relative paths. */
export function afterLoginTarget() {
  try {
    const next = new URL(location.href).searchParams.get('next');
    if (next && next.startsWith('/') && !next.startsWith('//')) return next;
  } catch { /* ignore */ }
  return 'trips.html';
}

export async function signOut() {
  try { await api.logout(); } catch { /* sign out locally regardless */ }
  cached = null;
  location.href = 'login.html';
}

export function clearCachedUser() {
  cached = null;
}
