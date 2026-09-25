// The guard behind "every word lives in js/i18n/en.js". Run by `npm run check`.
//
//   node scripts/i18n-check.mjs          check
//   node scripts/i18n-check.mjs --fix    also write the English into <head> elements
//
// It fails when:
//   1. a key is used in js/ or *.html but is not in en.js (the page would show the key);
//   2. a key in en.js is used nowhere (a word nobody sees, still to translate);
//   3. another language file has a key en.js does not (a typo);
//   4. a toast, alert or textContent is given a sentence in code instead of a key;
//   5. the English kept in <title> / <meta> for crawlers has drifted from en.js.
// 5 exists because link previews and search engines read the HTML without running
// any script, so those few elements keep their English in the file; the check is
// what keeps that copy honest.
import { readdirSync, readFileSync, writeFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import en from '../js/i18n/en.js';

const fix = process.argv.includes('--fix');
const problems = [];
const fail = (message) => problems.push(message);

const walk = (dir) => readdirSync(dir).flatMap((name) => {
  const path = join(dir, name);
  return statSync(path).isDirectory() ? walk(path) : [path];
});
const jsFiles = walk('js').filter((f) => f.endsWith('.js') && !f.startsWith('js/i18n/'));
const htmlFiles = readdirSync('.').filter((f) => f.endsWith('.html'));
const sources = [...jsFiles, ...htmlFiles].map((file) => ({ file, text: readFileSync(file, 'utf8') }));

// ── 1. keys used
const KEY = '[\\w][\\w-]*(?:\\.[\\w-]+)+';
const used = new Map(); // key -> first place
const note = (key, file) => { if (!used.has(key)) used.set(key, file); };
for (const { file, text } of sources) {
  for (const m of text.matchAll(new RegExp(`\\$?\\bth?\\(\\s*'(${KEY})'`, 'g'))) note(m[1], file);
  for (const m of text.matchAll(new RegExp(`data-i18n(?:-[a-z-]+)?="(${KEY})"`, 'g'))) note(m[1], file);
  // Keys held in tables and picked later: any quoted literal that is exactly a key of ours.
  for (const m of text.matchAll(new RegExp(`'(${KEY})'`, 'g'))) if (hasKey(m[1])) note(m[1], file);
}
function hasKey(key) {
  return key in en || `${key}.other` in en || `${key}.one` in en;
}
for (const [key, file] of used) if (!hasKey(key)) fail(`${file}: uses '${key}', which en.js does not have`);

// ── 2. keys unused
const usedRoots = new Set([...used.keys()].flatMap((k) => [k, k.replace(/\.(zero|one|two|few|many|other)$/, '')]));
for (const key of Object.keys(en)) {
  const root = key.replace(/\.(zero|one|two|few|many|other)$/, '');
  if (!usedRoots.has(key) && !usedRoots.has(root)) fail(`en.js: '${key}' is not used anywhere`);
}

// ── 3. other languages
for (const file of readdirSync('js/i18n').filter((f) => f.endsWith('.js') && f !== 'index.js' && f !== 'en.js')) {
  const table = (await import(`../js/i18n/${file}`)).default;
  for (const key of Object.keys(table)) if (!(key in en)) fail(`js/i18n/${file}: '${key}' is not in en.js`);
}

// ── 4. sentences in code
const SENTENCE = /[A-Za-z]{3,}[^'"`]*\s[A-Za-z]{2,}/;
for (const { file, text } of sources) {
  if (file === 'js/countries.js') continue; // the reviewed English table itself
  const lines = text.split('\n');
  lines.forEach((line, i) => {
    if (/^\s*(\/\/|\*|\/\*)/.test(line)) return;
    for (const m of line.matchAll(/toast\.(?:success|error|warning)\(\s*(['"`])((?:(?!\1).)*)\1/g)) {
      if (SENTENCE.test(m[2].replace(/\$\{[^}]*\}/g, ''))) fail(`${file}:${i + 1}: toast holds a sentence, not a key: ${m[2].slice(0, 60)}`);
    }
    for (const m of line.matchAll(/\b(?:alert|confirm)\(\s*(['"`])((?:(?!\1).)*)\1/g)) {
      if (SENTENCE.test(m[2])) fail(`${file}:${i + 1}: alert/confirm holds a sentence: ${m[2].slice(0, 60)}`);
    }
    // this.somethingError = 'Give the trip a title.'  /  message = `${name} updated`
    for (const m of line.matchAll(/\b\w*(?:Error|Message|Hint|Note|Warning)\s*=\s*(['"`])((?:(?!\1).)*)\1/g)) {
      if (SENTENCE.test(m[2].replace(/\$\{[^}]*\}/g, ''))) fail(`${file}:${i + 1}: message held in code: ${m[2].slice(0, 60)}`);
    }
    // return 'Sentence here'  /  return `${n} things`  /  ? 'Sentence' : 'Other sentence'
    for (const m of line.matchAll(/(?:\breturn\s+|[?:]\s+)(['"`])((?:(?!\1).)*)\1/g)) {
      const words = m[2].replace(/\$\{[^}]*\}/g, ' ');
      if (SENTENCE.test(words) && /[A-Z][a-z]+ [a-z]+|[a-z]+ [a-z]+ [a-z]+/.test(words) && !/^[a-z-]+( [a-z-]+)*$/.test(m[2]))
        fail(`${file}:${i + 1}: sentence returned from code: ${m[2].slice(0, 60)}`);
    }
    // A template literal that mixes a value with a word: `${n} item${n === 1 ? '' : 's'}`, `${n} places`.
    // Words belong in the message file, where the plural forms live.
    for (const m of line.matchAll(/`((?:[^`\\]|\\.)*)`/g)) {
      const statics = m[1].replace(/\$\{(?:[^{}]|\{[^{}]*\})*\}/g, '\u0000');
      if (/\$\{/.test(m[1]) && /(?:^|\u0000|[\s(])[a-z]{3,}(?:\s|\u0000|$)/i.test(statics) && !/^[\w\s./#:?=&%\-\[\]*,;()<>|\\^$\u0000]*$/.test(statics.replace(/[a-z]{3,}/gi, '')) === false
          && /\u0000\s*[a-z]{3,}|[a-z]{3,}\s*\u0000/i.test(statics) && !/[/#.=]|px|%;|width|height|color|class|href|query|api|trip\.html|\.js/.test(statics)) {
        fail(`${file}:${i + 1}: words mixed with values in code: ${m[1].slice(0, 60)}`);
      }
    }
    for (const m of line.matchAll(/\.textContent\s*=\s*(['"`])((?:(?!\1).)*)\1/g)) {
      if (SENTENCE.test(m[2].replace(/\$\{[^}]*\}/g, ''))) fail(`${file}:${i + 1}: textContent set to a sentence: ${m[2].slice(0, 60)}`);
    }
  });
}

// ── 5. English kept in <head> for crawlers
const decode = (s) => s.replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;/g, "'");
const encodeText = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
const encodeAttr = (s) => encodeText(s).replace(/"/g, '&quot;');
for (const file of htmlFiles) {
  let text = readFileSync(file, 'utf8');
  const head = text.slice(0, text.indexOf('</head>'));
  let rewritten = head;
  rewritten = rewritten.replace(/<title([^>]*)data-i18n="([^"]+)"([^>]*)>([^<]*)<\/title>/g, (whole, a, key, b, inner) => {
    const english = en[key];
    if (english == null) return whole;
    if (decode(inner) !== english) { if (!fix) fail(`${file}: <title> English differs from en.js '${key}'`); return `<title${a}data-i18n="${key}"${b}>${encodeText(english)}</title>`; }
    return whole;
  });
  rewritten = rewritten.replace(/<meta\b[^>]*\bdata-i18n-content="([^"]+)"[^>]*>/g, (whole, key) => {
    const english = en[key];
    if (english == null) return whole;
    const has = /(?<![\w-])content="([^"]*)"/.exec(whole);
    if (has && decode(has[1]) === english) return whole;
    if (!fix) fail(`${file}: <meta> English differs from en.js '${key}'`);
    return has ? whole.replace(/(?<![\w-])content="[^"]*"/, `content="${encodeAttr(english)}"`)
      : whole.replace(/\s*data-i18n-content=/, ` content="${encodeAttr(english)}" data-i18n-content=`);
  });
  if (fix && rewritten !== head) writeFileSync(file, rewritten + text.slice(head.length));
}

if (problems.length) {
  console.error(problems.join('\n'));
  console.error(`\n${problems.length} i18n problem(s).`);
  process.exit(1);
}
console.log(`i18n: ${Object.keys(en).length} keys, all used, all present.`);
