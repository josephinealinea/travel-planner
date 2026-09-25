/**
 * How a tab's detail view opens: a centred popup, or a side panel.
 *
 * Both styles are wanted while the choice is still open, so this is a setting
 * rather than a rewrite. Only geometry moves — the markup, the Alpine state and
 * the close handlers are identical in both modes.
 */
import { DEFAULT_PANEL_MODE } from './config.js';

export { DEFAULT_PANEL_MODE };

export const PANEL_MODES = {
  popup: {},
  side: {},
};

const STORAGE_KEY = 'panelMode';

export function savedPanelMode() {
  try {
    const saved = localStorage.getItem(STORAGE_KEY);
    return PANEL_MODES[saved] ? saved : DEFAULT_PANEL_MODE;
  } catch {
    // Private browsing can throw on access, not just return null.
    return DEFAULT_PANEL_MODE;
  }
}

export function applyPanelMode(mode) {
  const next = PANEL_MODES[mode] ? mode : DEFAULT_PANEL_MODE;

  document.body.classList.remove(
    ...Object.keys(PANEL_MODES).map((name) => `panels-${name}`));
  document.body.classList.add(`panels-${next}`);

  try { localStorage.setItem(STORAGE_KEY, next); } catch { /* private mode */ }

  document.dispatchEvent(new CustomEvent('panelmodechange', { detail: { mode: next } }));
  return next;
}
