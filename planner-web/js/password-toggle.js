/**
 * The show/hide eye control on every password input in the app.
 *
 * Toggling the input's type is the whole trick, but doing it without losing the
 * caret takes a little care — hence the selectionStart save and restore.
 */
const SHOW_ICON = '👁';
const HIDE_ICON = '🙈';

function attach(input) {
  if (input.dataset.toggleReady === 'true') return;
  input.dataset.toggleReady = 'true';

  const wrapper = input.closest('.password-field');
  if (!wrapper) return;

  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'password-toggle';
  button.textContent = SHOW_ICON;
  button.setAttribute('aria-label', 'Show password');
  button.setAttribute('aria-pressed', 'false');
  // Keyboard reachable, and its aria-pressed state announces whether the
  // password is currently visible.
  button.tabIndex = 0;

  button.addEventListener('click', () => {
    const revealing = input.type === 'password';
    const caret = input.selectionStart;

    input.type = revealing ? 'text' : 'password';
    button.textContent = revealing ? HIDE_ICON : SHOW_ICON;
    button.setAttribute('aria-label', revealing ? 'Hide password' : 'Show password');
    button.setAttribute('aria-pressed', String(revealing));

    // Changing type moves focus and caret in some browsers; put them back.
    input.focus();
    if (caret != null) {
      try { input.setSelectionRange(caret, caret); } catch { /* type may not support it */ }
    }
  });

  wrapper.appendChild(button);
}

/** Wires up every password field present now, and any added later. */
export function initPasswordToggles(root = document) {
  root.querySelectorAll('.password-field input[type="password"]').forEach(attach);

  // Alpine renders some fields after first paint (the account page, drawers).
  const observer = new MutationObserver(() => {
    document.querySelectorAll('.password-field input[type="password"]').forEach(attach);
  });
  observer.observe(document.body, { childList: true, subtree: true });
}

/** 0-4, used by the strength meter on the change-password screen. */
export function passwordScore(value) {
  if (!value) return 0;
  let score = 0;
  if (value.length >= 8) score += 1;
  if (value.length >= 12) score += 1;
  if (/[a-z]/.test(value) && /[A-Z0-9]/.test(value)) score += 1;
  if (/[^a-zA-Z0-9]/.test(value)) score += 1;
  return Math.min(score, 4);
}
