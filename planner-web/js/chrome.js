import { renderSelector, initThemeSelector, applyTheme, savedTheme } from './theme-selector.js';
import { applyPanelMode, savedPanelMode } from './panel-mode.js';
import { initPasswordToggles } from './password-toggle.js';
import { signOut } from './session.js';

/**
 * The shared header and footer. Injected rather than duplicated into seven HTML
 * files, so the nav and the theme selector stay identical everywhere.
 *
 * During the forced password change the nav links are left out on purpose:
 * there is nowhere else to go until a password is chosen.
 */
export function renderChrome({ user = null, active = '', minimal = false } = {}) {
  const header = document.querySelector('#site-header');
  if (header) {
    const links = (user && !minimal) ? `
      <a class="nav-link${active === 'trips' ? ' active' : ''}" href="trips.html">My Trips</a>
      <a class="nav-link${active === 'account' ? ' active' : ''}" href="account.html">Account</a>
      <span class="nav-user" title="${escapeAttr(user.email)}">${escapeHtml(user.displayName)}</span>
      <button type="button" class="btn btn-ghost btn-sm" id="sign-out">Sign out</button>` : '';

    header.className = 'site-header';
    header.innerHTML = `
      <div class="site-header-inner">
        <a class="site-brand" href="${user ? 'trips.html' : 'login.html'}"><span aria-hidden="true">🧭</span> <span>Travel Planner</span></a>
        <nav class="site-nav">
          ${links}
          <span id="theme-selector-slot"></span>
        </nav>
      </div>`;

    header.querySelector('#sign-out')?.addEventListener('click', signOut);

    const slot = header.querySelector('#theme-selector-slot');
    if (slot) {
      renderSelector(slot);
      applyTheme(savedTheme());
      initThemeSelector(header);
    }
  }

  const footer = document.querySelector('#site-footer');
  if (footer) {
    footer.className = 'site-footer';
    footer.textContent = 'Travel Planner — plan a trip together, publish it when you are ready.';
  }

  // Panels are hidden at load, so unlike the theme this needs no pre-paint
  // script — there is nothing visible to flash.
  applyPanelMode(savedPanelMode());

  initPasswordToggles();
}

export function escapeHtml(text) {
  if (text == null) return '';
  return String(text)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

export const escapeAttr = escapeHtml;

/** Reads ?id= from the URL, for the trip workspace. */
export function queryParam(name) {
  try {
    return new URL(location.href).searchParams.get(name);
  } catch {
    return null;
  }
}
