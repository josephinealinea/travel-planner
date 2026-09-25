// Run with: node --test scripts/i18n.test.mjs   (also part of `npm run check`)
import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import {
  t, th, setLanguage, currentLanguage, registerLanguage, resetForTests, storedLanguage,
} from '../js/i18n/index.js';
import en from '../js/i18n/en.js';

// A made-up language, only for these tests. It is never shipped.
const xx = {
  'test.plain': '[xx] Plain',
  'test.hello': '[xx] Hello {name}',
  'test.trips.one': '[xx] {count} trip',
  'test.trips.other': '[xx] {count} trips',
};

beforeEach(() => {
  resetForTests();
  registerLanguage('xx', xx);
});

// Only in English, so a language that lacks it has to fall back.
en['test.plain'] = 'Plain';
en['test.hello'] = 'Hello {name}';
en['test.englishOnly'] = 'Only in English';
en['test.trips.one'] = '{count} trip';
en['test.trips.other'] = '{count} trips';
en['test.bold'] = 'Deleting <strong>{title}</strong> for good';

test('English is the default', () => {
  assert.equal(currentLanguage(), 'en');
  assert.equal(t('test.plain'), 'Plain');
});

test('placeholders are filled from params', () => {
  assert.equal(t('test.hello', { name: 'Sam' }), 'Hello Sam');
});

test('a value is inserted literally, never read as replacement syntax', () => {
  // "$&" and "$1" mean something to String.replace; a trip called "Cost $& more" must not.
  assert.equal(t('test.hello', { name: 'Cost $& $1 {name}' }), 'Hello Cost $& $1 {name}');
});

test('a placeholder with no value is left visible rather than printed as "undefined"', () => {
  assert.equal(t('test.hello', {}), 'Hello {name}');
  assert.equal(t('test.hello'), 'Hello {name}');
});

test('a zero is a value', () => {
  assert.equal(t('test.hello', { name: 0 }), 'Hello 0');
});

test('a key nobody wrote shows as the key, so the gap is visible', () => {
  assert.equal(t('no.such.key'), 'no.such.key');
});

test('switching language changes the words', async () => {
  assert.equal(await setLanguage('xx'), true);
  assert.equal(currentLanguage(), 'xx');
  assert.equal(t('test.plain'), '[xx] Plain');
  assert.equal(t('test.hello', { name: 'Sam' }), '[xx] Hello Sam');
});

test('a key the language lacks falls back to English', async () => {
  await setLanguage('xx');
  assert.equal(t('test.englishOnly'), 'Only in English');
});

test('a language we have no words for is English, and says so', async () => {
  assert.equal(await setLanguage('fr'), false);
  assert.equal(currentLanguage(), 'en');
});

test('a language code cannot be a path', async () => {
  assert.equal(await setLanguage('../../etc/passwd'), false);
  assert.equal(await setLanguage('en/../en'), false);
  assert.equal(currentLanguage(), 'en');
});

test('a count picks the plural form the language uses', async () => {
  assert.equal(t('test.trips', { count: 1 }), '1 trip');
  assert.equal(t('test.trips', { count: 3 }), '3 trips');
  assert.equal(t('test.trips', { count: 0 }), '0 trips');
  await setLanguage('xx');
  assert.equal(t('test.trips', { count: 1 }), '[xx] 1 trip');
});

test('nothing is remembered where there is no storage', () => {
  assert.equal(storedLanguage(), null);
});

// th() is t() for a sentence that carries markup of its own. The markup comes
// from us; the values come from members, so they are escaped.
test('th escapes the values but not the sentence', () => {
  assert.equal(th('test.bold', { title: 'Peru' }), 'Deleting <strong>Peru</strong> for good');
  assert.equal(
    th('test.bold', { title: '<img src=x onerror=alert(1)> & "q"' }),
    'Deleting <strong>&lt;img src=x onerror=alert(1)&gt; &amp; &quot;q&quot;</strong> for good');
});

test('th still inserts values literally', () => {
  assert.equal(th('test.bold', { title: '$& $1' }), 'Deleting <strong>$&amp; $1</strong> for good');
});
