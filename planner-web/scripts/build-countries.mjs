// Writes js/countries.js's table to the API as publish/countries.json, the copy
// the published page inlines. `--check` compares instead of writing, and exits
// 1 when they differ.
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { COUNTRY_NAMES } from '../js/countries.js';

const target = new URL('../../planner-api/src/main/resources/publish/countries.json', import.meta.url);
const wanted = JSON.stringify(COUNTRY_NAMES) + '\n';

if (process.argv.includes('--check')) {
  if (!existsSync(target)) {
    console.error('publish/countries.json is missing — run `npm run countries`.');
    process.exit(1);
  }
  if (readFileSync(target, 'utf8') !== wanted) {
    console.error('publish/countries.json is out of step with js/countries.js — run `npm run countries`.');
    process.exit(1);
  }
  console.log('countries.json matches countries.js.');
} else {
  writeFileSync(target, wanted);
  console.log(`Wrote ${Object.keys(COUNTRY_NAMES).length} countries.`);
}
