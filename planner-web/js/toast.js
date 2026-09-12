/** Brief confirmations and failures, bottom-centre. */

const LIFETIME_MS = 4000;

function stack() {
  let node = document.querySelector('.toast-stack');
  if (!node) {
    node = document.createElement('div');
    node.className = 'toast-stack';
    node.setAttribute('role', 'status');
    node.setAttribute('aria-live', 'polite');
    document.body.appendChild(node);
  }
  return node;
}

function show(message, variant) {
  const toast = document.createElement('div');
  toast.className = `toast${variant ? ` toast-${variant}` : ''}`;
  toast.textContent = message;
  stack().appendChild(toast);
  setTimeout(() => toast.remove(), LIFETIME_MS);
}

export const toast = {
  show: (message) => show(message),
  success: (message) => show(message, 'success'),
  error: (message) => show(message, 'error'),
};
