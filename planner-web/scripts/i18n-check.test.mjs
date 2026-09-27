// Run with: node --test scripts/i18n-check.test.mjs   (also part of `npm run check`)
//
// Proves the placeholder-naming rule in scripts/i18n-check.mjs (check 6) really
// fails when it should. Each case builds a tiny throwaway project (its own
// en.js, one page, a copy of the check) and runs the real script against it, so
// nothing here depends on what the real planner happens to contain today.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { copyFileSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

const CHECK = resolve('scripts/i18n-check.mjs');

/** Runs the check over a fake project; returns { status, output }. */
function run({ en, html, js = '' }) {
  const dir = mkdtempSync(join(tmpdir(), 'i18n-check-'));
  try {
    mkdirSync(join(dir, 'scripts'));
    mkdirSync(join(dir, 'js', 'i18n'), { recursive: true });
    writeFileSync(join(dir, 'package.json'), '{"type":"module"}');
    copyFileSync(CHECK, join(dir, 'scripts', 'i18n-check.mjs'));
    const table = Object.entries(en).map(([k, v]) => `  '${k}': ${JSON.stringify(v)},`).join('\n');
    writeFileSync(join(dir, 'js', 'i18n', 'en.js'), `export default {\n${table}\n};\n`);
    writeFileSync(join(dir, 'js', 'page.js'), js);
    writeFileSync(join(dir, 'index.html'), `<!doctype html><html><head></head><body>${html}</body></html>`);
    const result = spawnSync(process.execPath, ['scripts/i18n-check.mjs'], { cwd: dir, encoding: 'utf8' });
    return { status: result.status, output: `${result.stdout}${result.stderr}` };
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

test('a placeholder named <area>.placeholder.<field> passes', () => {
  const { status, output } = run({
    en: { 'trip.placeholder.destination': 'Cusco' },
    html: '<input data-i18n-placeholder="trip.placeholder.destination">',
  });
  assert.equal(status, 0, output);
});

test('placeholder text under any other name fails and says how to name it', () => {
  const { status, output } = run({
    en: { 'trip.destination': 'Cusco' },
    html: '<input data-i18n-placeholder="trip.destination">',
  });
  assert.equal(status, 1);
  assert.match(output, /'trip\.destination' is used as placeholder text/);
  assert.match(output, /<area>\.placeholder\.<field>/);
});

test('an Alpine :placeholder bound to $t() is held to the same rule', () => {
  const bad = run({
    en: { 'trip.destination': 'Cusco' },
    html: `<input :placeholder="$t('trip.destination')">`,
  });
  assert.equal(bad.status, 1);
  assert.match(bad.output, /'trip\.destination' is used as placeholder text/);

  const good = run({
    en: { 'trip.placeholder.destination': 'Cusco' },
    html: `<input :placeholder="$t('trip.placeholder.destination')">`,
  });
  assert.equal(good.status, 0, good.output);
});

test('a .placeholder. key used as ordinary label text fails', () => {
  const { status, output } = run({
    en: { 'trip.placeholder.destination': 'Cusco' },
    html: '<label data-i18n="trip.placeholder.destination"></label>',
  });
  assert.equal(status, 1);
  assert.match(output, /'trip\.placeholder\.destination' is a placeholder key but is used as ordinary text/);
});

test('a .placeholder. key read through t() in code fails, unless the line is about a placeholder', () => {
  const asText = run({
    en: { 'trip.placeholder.destination': 'Cusco' },
    html: '',
    js: `export const label = () => t('trip.placeholder.destination');\n`,
  });
  assert.equal(asText.status, 1);
  assert.match(asText.output, /is a placeholder key but is used as ordinary text/);

  const asPlaceholder = run({
    en: { 'trip.placeholder.destination': 'Cusco' },
    html: '',
    js: `export const setPlaceholder = (el) => { el.placeholder = t('trip.placeholder.destination'); };\n`,
  });
  assert.equal(asPlaceholder.status, 0, asPlaceholder.output);
});

test('the same .placeholder. key may be used by several fields', () => {
  const { status, output } = run({
    en: { 'trip.placeholder.note': 'Anything worth remembering' },
    html: '<textarea data-i18n-placeholder="trip.placeholder.note"></textarea>'
      + '<textarea data-i18n-placeholder="trip.placeholder.note"></textarea>',
  });
  assert.equal(status, 0, output);
});
