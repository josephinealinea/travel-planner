/**
 * Every word the planner shows comes through here.
 *
 *   t('trip.checklist.empty')                      -> the words, in the current language
 *   t('toast.removed', { name: 'Sam' })            -> "Sam removed"      ({name} is filled in)
 *   t('trips.count', { count: 3 })                 -> "3 trips"          (picks trips.count.one / .other)
 *
 * The words live in js/i18n/<language>.js — en.js is the English one, and a
 * second language is a second file of the same keys, nothing else. A key a
 * language lacks falls back to English; a key English lacks shows as the key
 * itself, so the gap is visible instead of blank (npm run check fails first).
 *
 * The language is the member's own choice, kept on their account and in
 * localStorage for the pages that load before anybody is signed in. Changing
 * it reloads the page: every string is read once when the page draws, so a
 * reload is the one way to be certain nothing is left in the old language.
 */
import en from './en.js';

export const DEFAULT_LANGUAGE = 'en';
const STORAGE_KEY = 'plannerLanguage';
// A code is two or three letters: never a path, so it is safe to import().
const CODE = /^[a-z]{2,3}$/;

const tables = { [DEFAULT_LANGUAGE]: en };
let language = DEFAULT_LANGUAGE;

/** For a language that is not a file, and for tests. Real languages are js/i18n/<code>.js. */
export function registerLanguage(code, table) {
  tables[code] = table;
}

export function currentLanguage() {
  return language;
}

export function storedLanguage() {
  try {
    const code = localStorage.getItem(STORAGE_KEY);
    return code && CODE.test(code) ? code : null;
  } catch {
    return null; // private browsing can throw on access, not just return null
  }
}

function remember(code) {
  try {
    if (code) localStorage.setItem(STORAGE_KEY, code);
    else localStorage.removeItem(STORAGE_KEY);
  } catch { /* nothing to do: the account holds it too */ }
}

async function load(code) {
  if (tables[code]) return tables[code];
  try {
    const module = await import(`./${code}.js`);
    tables[code] = module.default;
    return tables[code];
  } catch {
    return null;
  }
}

/**
 * Makes `code` the language of this page. Returns false, staying in English,
 * for a language we have no words for (a stale choice, a browser asking for
 * something we do not speak) — never an error, since nothing here is worth
 * breaking the page over.
 */
export async function setLanguage(code, { persist = false } = {}) {
  const wanted = String(code || '').toLowerCase();
  const table = CODE.test(wanted) ? await load(wanted) : null;
  language = table ? wanted : DEFAULT_LANGUAGE;
  if (typeof document !== 'undefined') document.documentElement.lang = language;
  if (persist) remember(table ? wanted : null);
  return Boolean(table);
}

/**
 * The language to open a page in before anyone is signed in: what this browser
 * last used here, else what it asks for, else English.
 */
export async function initI18n() {
  const asked = typeof navigator !== 'undefined' ? (navigator.language || '').split('-')[0] : '';
  for (const code of [storedLanguage(), asked]) {
    if (code && await setLanguage(code)) return language;
  }
  return setLanguage(DEFAULT_LANGUAGE).then(() => language);
}

function lookup(key) {
  const own = tables[language];
  return own && own[key] != null ? own[key] : en[key];
}

function pluralForm(count) {
  try {
    return new Intl.PluralRules(language).select(Number(count));
  } catch {
    return 'other';
  }
}

function pattern(key, params) {
  let found;
  if (params && params.count != null) {
    found = lookup(`${key}.${pluralForm(params.count)}`) ?? lookup(`${key}.other`);
  }
  return found ?? lookup(key);
}

function fill(text, params, escape) {
  if (text == null) return null;
  if (!params) return text;
  // A function, not a string: "$&" and "$1" in a value would otherwise be
  // read by replace() as instructions, and a trip can be called anything.
  return text.replace(/\{(\w+)\}/g, (whole, name) => {
    if (params[name] == null) return whole;
    return escape ? escapeHtml(String(params[name])) : String(params[name]);
  });
}

function escapeHtml(text) {
  return text.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
}

/** The words for `key`. See the file's header for the forms. */
export function t(key, params) {
  return fill(pattern(key, params), params, false) ?? key;
}

/**
 * Like t(), for a sentence that has markup of its own ("Deleting <strong>{title}</strong>").
 * The markup is ours; the values are somebody's, so they are escaped. Bind it
 * with x-html / data-i18n-html, never x-text.
 */
export function th(key, params) {
  return fill(pattern(key, params), params, true) ?? escapeHtml(key);
}

const ATTRIBUTES = ['placeholder', 'aria-label', 'title', 'alt', 'content', 'value'];
const MARKED = ['[data-i18n]', '[data-i18n-html]', ...ATTRIBUTES.map((a) => `[data-i18n-${a}]`)].join(',');

/** Translates one element from whichever data-i18n markers it carries. */
function translateElement(el) {
  if (el.hasAttribute('data-i18n')) el.textContent = t(el.getAttribute('data-i18n'));
  if (el.hasAttribute('data-i18n-html')) el.innerHTML = t(el.getAttribute('data-i18n-html'));
  for (const attribute of ATTRIBUTES) {
    const key = el.getAttribute(`data-i18n-${attribute}`);
    if (key != null) el.setAttribute(attribute, t(key));
  }
}

/**
 * Fills every element that names its words instead of holding them:
 *   data-i18n="key"                 its text
 *   data-i18n-html="key"            its markup, for a sentence with a <strong> or <code> in it
 *   data-i18n-<attribute>="key"     placeholder, aria-label, title, alt, content, value
 */
export function applyTranslations(root = document) {
  root.querySelectorAll(MARKED).forEach(translateElement);
}

/**
 * The signed-in member's own choice beats what the browser remembers or asks
 * for. Called once the user is known and before anything is drawn.
 */
export async function useAccountLanguage(user) {
  const chosen = user && user.languageCode;
  if (chosen && chosen !== language) await setLanguage(chosen, { persist: true });
  // An account with no choice keeps whatever this browser last used.
  return language;
}

let observer = null;

/**
 * Translates the page now, and everything Alpine draws later: an x-for row or
 * an x-if block is cloned from a template after load, carrying its data-i18n
 * markers with it, so a one-off pass would leave those in no language at all.
 */
export function startTranslating(root = document) {
  applyTranslations(root);
  if (observer || typeof MutationObserver === 'undefined') return;
  observer = new MutationObserver((records) => {
    for (const record of records) {
      record.addedNodes.forEach((node) => {
        // Text nodes are what applyTranslations itself writes; only elements can carry markers.
        if (node.nodeType !== 1) return;
        if (node.matches(MARKED)) translateElement(node);
        applyTranslations(node);
      });
    }
  });
  observer.observe(root.body || root, { childList: true, subtree: true });
}

/** Alpine's $t and $th, for x-text="$t('key', { name })" and x-html="$th(...)". Call before Alpine starts. */
export function registerAlpineMagics() {
  document.addEventListener('alpine:init', () => {
    window.Alpine.magic('t', () => t);
    window.Alpine.magic('th', () => th);
  });
}

/** Test-only: back to a clean English page. */
export function resetForTests() {
  for (const code of Object.keys(tables)) if (code !== DEFAULT_LANGUAGE) delete tables[code];
  language = DEFAULT_LANGUAGE;
  try { localStorage.removeItem(STORAGE_KEY); } catch { /* no storage */ }
}
