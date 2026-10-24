// Filter list download + on-disk cache. On Android this becomes a WorkManager job that
// refreshes the lists over unmetered networks every few days.
import fs from 'node:fs/promises';
import path from 'node:path';

// EasyList's site is the canonical source, but the GitHub mirrors carry the same compiled
// files. Fanboy's Annoyance List already includes the EasyList Cookie List.
export const DEFAULT_LISTS = [
  { name: 'fanboy-annoyance.txt', url: 'https://raw.githubusercontent.com/easylist/easylist/gh-pages/fanboy-annoyance.txt' },
  { name: 'annoyances-cookies.txt', url: 'https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/annoyances-cookies.txt' },
  { name: 'annoyances-others.txt', url: 'https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/annoyances-others.txt' },
];
// uBO scriptlets/redirect resources in the format adblock-rust's useResources() expects.
export const RESOURCES = { name: 'resources.json', url: 'https://raw.githubusercontent.com/brave/adblock-resources/master/dist/resources.json' };

const MAX_AGE_MS = 4 * 24 * 3600 * 1000;

async function fetchCached(dir, { name, url }, { offline = false } = {}) {
  const file = path.join(dir, name);
  let stat = null;
  try { stat = await fs.stat(file); } catch {}
  if (stat && (offline || Date.now() - stat.mtimeMs < MAX_AGE_MS)) return fs.readFile(file, 'utf8');
  try {
    const res = await fetch(url);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const text = await res.text();
    await fs.mkdir(dir, { recursive: true });
    await fs.writeFile(file, text);
    return text;
  } catch (err) {
    if (stat) return fs.readFile(file, 'utf8'); // stale copy beats no rules
    throw new Error(`could not fetch ${url}: ${err.message}`);
  }
}

export async function loadLists(dir, opts = {}) {
  const lists = [];
  for (const l of opts.lists ?? DEFAULT_LISTS) lists.push({ name: l.name, text: await fetchCached(dir, l, opts) });
  const resources = JSON.parse(await fetchCached(dir, RESOURCES, opts));
  return { lists, resources };
}
