// Fails when a role="dialog" element lacks x-dialog, aria-labelledby or
// aria-modal — the silent opt-ins described in CLAUDE.md.
import { readdirSync, readFileSync } from 'node:fs';

let bad = 0;
for (const file of readdirSync('.').filter((f) => f.endsWith('.html'))) {
  const html = readFileSync(file, 'utf8');
  for (const m of html.matchAll(/<[a-z]+\b[^>]*role="dialog"[^>]*>/gs)) {
    const line = html.slice(0, m.index).split('\n').length;
    for (const attr of ['x-dialog', 'aria-labelledby', 'aria-modal']) {
      if (!m[0].includes(attr)) { console.error(`${file}:${line} dialog missing ${attr}`); bad++; }
    }
  }
}
if (bad) process.exit(1);
console.log('All dialogs carry x-dialog, aria-modal and aria-labelledby.');
