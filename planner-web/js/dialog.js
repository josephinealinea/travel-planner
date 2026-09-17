/**
 * x-dialog: what a modal owes a keyboard and a screen reader.
 *
 * Put it on the role="dialog" element with the same expression its backdrop's
 * x-show uses. While that is truthy the dialog takes focus, keeps Tab inside
 * itself, and locks the page behind it from scrolling; when it goes falsy,
 * focus returns to whatever opened it. Without this, focus stayed on the
 * button behind the backdrop and Tab walked the page nobody could see.
 *
 * Dialogs can stack — the completion confirm opens over the checklist drawer —
 * so open dialogs are a stack and only the top one traps.
 */

const FOCUSABLE = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled]):not([type="hidden"])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]',
].join(',');

const stack = [];

/** Rendered, tabbable descendants. x-show hides with display:none, so offsetParent is the visibility test (CLAUDE.md, Traps). */
function tabbables(root) {
  return [...root.querySelectorAll(FOCUSABLE)]
    .filter((el) => el.tabIndex >= 0 && el.offsetParent !== null);
}

function trapTab(event) {
  if (event.key !== 'Tab' || !stack.length) return;
  const { el } = stack[stack.length - 1];
  const items = tabbables(el);
  if (!items.length) {
    event.preventDefault();
    el.focus();
    return;
  }
  const first = items[0];
  const last = items[items.length - 1];
  const active = document.activeElement;
  if (!el.contains(active)) {
    event.preventDefault();
    first.focus();
  } else if (event.shiftKey && (active === first || active === el)) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && active === last) {
    event.preventDefault();
    first.focus();
  }
}

function open(el) {
  if (stack.some((entry) => entry.el === el)) return;
  stack.push({ el, returnTo: document.activeElement });
  document.body.dataset.dialogOpen = '';

  // Two frames, not one: x-show reveals on the next frame, and a caller's own
  // focusWhenShown() lands on the frame after its $nextTick. Waiting one more
  // lets a field that caller chose keep the caret instead of being overridden.
  requestAnimationFrame(() => requestAnimationFrame(() => {
    // Frames pause in a background tab, so this can run long after the dialog
    // has closed again; focusing it then would undo close()'s focus return.
    if (!stack.some((entry) => entry.el === el)) return;
    if (el.contains(document.activeElement)) return;
    (tabbables(el)[0] || el).focus();
  }));
}

function close(el) {
  const index = stack.findIndex((entry) => entry.el === el);
  if (index === -1) return;
  const [{ returnTo }] = stack.splice(index, 1);
  if (!stack.length) delete document.body.dataset.dialogOpen;

  // The opener may have gone — a deleted row's Edit button, a tab switched
  // away from — and focusing a detached or hidden element does nothing.
  if (returnTo?.isConnected && returnTo.offsetParent !== null) returnTo.focus();
}

/** Call before Alpine loads: the directive is registered on alpine:init. */
export function registerDialogFocus() {
  document.addEventListener('keydown', trapTab);
  document.addEventListener('alpine:init', () => {
    window.Alpine.directive('dialog', (el, { expression }, { evaluateLater, effect, cleanup }) => {
      const isOpen = evaluateLater(expression);
      // Lets the dialog itself hold focus when it has nothing tabbable.
      if (!el.hasAttribute('tabindex')) el.setAttribute('tabindex', '-1');
      effect(() => isOpen((value) => (value ? open(el) : close(el))));
      cleanup(() => close(el));
    });
  });
}
