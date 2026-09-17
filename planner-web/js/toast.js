/**
 * Brief confirmations and failures, bottom-centre.
 *
 * Confirmations fade after a few seconds. Errors stay until dismissed: a
 * server message takes longer than four seconds to read, and nothing else on
 * the page records it. The two also go to different live regions — polite
 * for "Saved", assertive for "Could not save" — which have to exist before
 * anything is put in them, or a screen reader never announces the first one.
 */

const LIFETIME_MS = 4000;
const MAX_ERRORS = 3;

function regions() {
  let node = document.querySelector('.toast-stack');
  if (!node) {
    node = document.createElement('div');
    node.className = 'toast-stack';
    node.innerHTML = '<div class="toast-region" role="status" aria-live="polite"></div>'
                   + '<div class="toast-region" role="alert"></div>';
    document.body.appendChild(node);
  }
  const [polite, assertive] = node.children;
  return { polite, assertive };
}

function show(message, variant) {
  const toast = document.createElement('div');
  toast.className = `toast${variant ? ` toast-${variant}` : ''}`;

  if (variant !== 'error') {
    toast.textContent = message;
    regions().polite.appendChild(toast);
    setTimeout(() => toast.remove(), LIFETIME_MS);
    return;
  }

  const text = document.createElement('span');
  text.textContent = message;
  const dismiss = document.createElement('button');
  dismiss.type = 'button';
  dismiss.className = 'toast-close';
  dismiss.setAttribute('aria-label', 'Dismiss');
  dismiss.textContent = '✕';
  dismiss.addEventListener('click', () => toast.remove());
  toast.append(text, dismiss);

  const { assertive } = regions();
  assertive.appendChild(toast);
  // A run of failures should not wallpaper the screen; the oldest go first.
  while (assertive.children.length > MAX_ERRORS) assertive.firstElementChild.remove();
}

export const toast = {
  show: (message) => show(message),
  success: (message) => show(message, 'success'),
  error: (message) => show(message, 'error'),
};
