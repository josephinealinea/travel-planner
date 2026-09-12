import { api } from './api.js';
import { renderChrome } from './chrome.js';
import { requireUser } from './session.js';

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
  await api.initCsrf();

  const user = await requireUser();
  if (!user) return null;               // requireUser is already redirecting

  renderChrome({ user, active });

  // Register components before Alpine exists, so it finds them on start.
  Object.entries(component).forEach(([name, factory]) => {
    window[name] = factory;
  });

  // Alpine's CDN build starts itself on load, including when the document has
  // already finished parsing.
  await import('../vendor/alpine.min.js');

  return user;
}
