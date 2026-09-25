import { api } from './api.js';
import { renderChrome } from './chrome.js';
import { registerDialogFocus } from './dialog.js';
import { requireUser } from './session.js';
import { initI18n, useAccountLanguage, registerAlpineMagics } from './i18n/index.js';

/**
 * Page startup for the Alpine-driven pages.
 *
 * The ordering here matters. A module script that awaits anything at the top
 * level can finish after DOMContentLoaded, so a deferred Alpine tag would
 * initialise before the page had registered its component and every x-data
 * would fail. Loading Alpine from here — after the component is registered —
 * removes the race entirely rather than trying to win it.
 */
export async function bootPage({ active = '', component = {} } = {}) {
  // Before the first request, so it can ask the API for the right language,
  // and before anything is drawn.
  await initI18n();
  await api.initCsrf();

  const user = await requireUser();
  if (!user) return null;               // requireUser is already redirecting

  // The member's own choice beats what this browser last used.
  await useAccountLanguage(user);

  renderChrome({ user, active });

  // Register components before Alpine exists, so it finds them on start.
  Object.entries(component).forEach(([name, factory]) => {
    window[name] = factory;
  });

  // Directives, like components, have to exist before Alpine starts.
  registerDialogFocus();
  registerAlpineMagics();

  // Alpine's CDN build starts itself on load, including when the document has
  // already finished parsing.
  await import('../vendor/alpine.min.js');

  return user;
}
