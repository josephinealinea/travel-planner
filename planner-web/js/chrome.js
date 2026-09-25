import { renderSelector, initThemeSelector, applyTheme, savedTheme } from './theme-selector.js';
import { applyPanelMode, savedPanelMode } from './panel-mode.js';
import { initPasswordToggles } from './password-toggle.js';
import { signOut } from './session.js';
import { t, startTranslating } from './i18n/index.js';

/**
 * The shared header and footer. Injected rather than duplicated into seven HTML
 * files, so the nav and the theme selector stay identical everywhere.
 *
 * During the forced password change the nav links are left out on purpose:
 * there is nowhere else to go until a password is chosen.
 */
export function renderChrome({ user = null, active = '', minimal = false } = {}) {
  // First thing a keyboard user reaches: skips the header on every page.
  const main = document.querySelector('main');
  if (main && !document.querySelector('.skip-link')) {
    if (!main.id) main.id = 'main-content';
    main.tabIndex = -1;
    const skip = document.createElement('a');
    skip.className = 'skip-link';
    skip.href = `#${main.id}`;
    skip.textContent = t('chrome.skipToMain');
    // Move focus instead of following the link: the trip page routes its tabs
    // by location.hash, so navigating to #main-content would switch tab.
    skip.addEventListener('click', (event) => {
      event.preventDefault();
      main.focus();
      main.scrollIntoView();
    });
    document.body.prepend(skip);
  }

  const header = document.querySelector('#site-header');
  if (header) {
    const links = (user && !minimal) ? `
      <a class="nav-link${active === 'trips' ? ' active' : ''}" href="trips.html">${t('chrome.myTrips')}</a>
      <a class="nav-link${active === 'account' ? ' active' : ''}" href="account.html">${t('chrome.account')}</a>
      <span class="nav-user" title="${escapeAttr(user.email)}">${escapeHtml(user.displayName)}</span>
      <button type="button" class="btn btn-ghost btn-sm" id="sign-out">${t('chrome.signOut')}</button>` : '';

    header.className = 'site-header';
    header.innerHTML = `
      <div class="site-header-inner">
        <a class="site-brand" href="${user ? 'trips.html' : 'login.html'}"><span aria-hidden="true">🦙</span> <span class="site-brand-name">Travelling Llama</span></a>
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
    footer.textContent = t('chrome.footer');
  }

  // Panels are hidden at load, so unlike the theme this needs no pre-paint
  // script — there is nothing visible to flash.
  applyPanelMode(savedPanelMode());

  initPasswordToggles();

  // The page's own markup names its words by key; fill it in now, and keep
  // filling anything Alpine draws later. Every page reaches here once the
  // language is settled, so this is the one place that starts it.
  startTranslating();
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
