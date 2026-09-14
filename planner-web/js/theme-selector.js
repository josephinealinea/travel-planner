/**
 * Theme switching, following the same mechanism as the main site: one compiled
 * stylesheet per theme, all present as <link> elements with every one but the
 * active theme disabled, plus a body class and a remembered choice.
 *
 * Adding a theme means one entry here, one <link> in each page's head, one
 * token file and one entry point in scss/, plus the sass command in
 * package.json and StaticSiteRenderer.THEMES if published pages should offer
 * it — nothing else.
 *
 * Retro-Game and Manila were removed from this registry but their sources were
 * kept (scss/themes/_retro-game.scss, scss/manila.scss and friends, plus their
 * :root[data-theme] blocks in the API's publish/page.css), so re-adding either
 * is those same edits and no restyling. A name that is no longer here degrades
 * on its own: savedTheme() and StaticSiteRenderer.safeTheme both fall back to
 * the default rather than failing, so anybody still holding "retro-game" in
 * localStorage, or a trip published in it, simply gets Minima.
 */
export const THEME_REGISTRY = {
  minima:       { stylesheetId: 'minima-css',     labelFull: 'Minima Theme',   labelShort: 'Minima', swatch: '#b45309' },
  y2k:          { stylesheetId: 'y2k-css',        labelFull: 'Y2K Theme',      labelShort: 'Y2K',    swatch: '#000080' },
  dark:         { stylesheetId: 'dark-css',       labelFull: 'Dark Theme',     labelShort: 'Dark',   swatch: '#5ad1ff' },
};

export const THEME_NAMES = Object.keys(THEME_REGISTRY);
export const DEFAULT_THEME = 'minima';
const STORAGE_KEY = 'selectedTheme';

export function savedTheme() {
  try {
    const saved = localStorage.getItem(STORAGE_KEY);
    return THEME_REGISTRY[saved] ? saved : DEFAULT_THEME;
  } catch {
    // Private browsing can throw on access, not just return null.
    return DEFAULT_THEME;
  }
}

export function applyTheme(name) {
  const theme = THEME_REGISTRY[name] ? name : DEFAULT_THEME;
  const config = THEME_REGISTRY[theme];

  document.body.classList.remove(...THEME_NAMES.map((n) => `theme-${n}`));
  document.body.classList.add(`theme-${theme}`);

  // Enable exactly one stylesheet and disable the rest.
  THEME_NAMES.forEach((n) => {
    const link = document.getElementById(THEME_REGISTRY[n].stylesheetId);
    if (link) link.disabled = n !== theme;
  });

  try { localStorage.setItem(STORAGE_KEY, theme); } catch { /* private mode */ }

  updateToggleLabel(theme);
  document.dispatchEvent(new CustomEvent('themechange', { detail: { theme } }));
  return theme;
}

function updateToggleLabel(theme) {
  const label = document.getElementById('current-theme');
  if (!label) return;
  const config = THEME_REGISTRY[theme] || THEME_REGISTRY[DEFAULT_THEME];
  const isMobile = window.innerWidth <= 768;
  label.textContent = isMobile ? config.labelShort : config.labelFull;
}

/** Builds the selector's markup so every page does not repeat it. */
export function renderSelector(container) {
  const options = THEME_NAMES.map((name) => {
    const { labelFull, labelShort, swatch } = THEME_REGISTRY[name];
    return `
      <button type="button" class="theme-option" data-theme="${name}" role="menuitemradio">
        <span class="theme-swatch" style="background:${swatch}"></span>
        <span class="label-full">${labelFull}</span><span class="label-short">${labelShort}</span>
      </button>`;
  }).join('');

  container.innerHTML = `
    <div class="theme-selector">
      <button id="theme-toggle" class="theme-toggle" aria-haspopup="true" aria-expanded="false">
        🎨 <span id="current-theme">Theme</span>
      </button>
      <div id="theme-dropdown" class="theme-dropdown" role="menu">${options}</div>
    </div>`;
}

export function initThemeSelector(root = document) {
  const toggle = root.querySelector('#theme-toggle');
  const dropdown = root.querySelector('#theme-dropdown');
  const options = root.querySelectorAll('.theme-option');
  const active = savedTheme();

  const markActive = (theme) => options.forEach((option) => {
    const isActive = option.dataset.theme === theme;
    option.classList.toggle('active', isActive);
    option.setAttribute('aria-checked', String(isActive));
  });

  markActive(active);

  const close = () => {
    dropdown?.classList.remove('show');
    toggle?.setAttribute('aria-expanded', 'false');
  };

  toggle?.addEventListener('click', (event) => {
    event.stopPropagation();
    const open = dropdown.classList.toggle('show');
    toggle.setAttribute('aria-expanded', String(open));
  });

  options.forEach((option) => option.addEventListener('click', () => {
    markActive(applyTheme(option.dataset.theme));
    close();
  }));

  document.addEventListener('click', (event) => {
    if (!event.target.closest('.theme-selector')) close();
  });

  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') close();
  });

  // Labels switch between long and short across the mobile breakpoint.
  window.addEventListener('resize', () => updateToggleLabel(savedTheme()));
}

/**
 * Applies the saved theme before anything renders. Called from an inline
 * script in each page's head so there is no flash of the wrong theme.
 */
export function bootTheme() {
  const theme = savedTheme();
  const config = THEME_REGISTRY[theme];
  THEME_NAMES.forEach((n) => {
    const link = document.getElementById(THEME_REGISTRY[n].stylesheetId);
    if (link) link.disabled = n !== theme;
  });
  document.documentElement.dataset.theme = theme;
  return theme;
}
