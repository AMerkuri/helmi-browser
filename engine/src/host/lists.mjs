// Filter list download + on-disk cache. On Android this becomes a WorkManager job that
// refreshes the lists over unmetered networks every few days.
import fs from 'node:fs/promises';
import path from 'node:path';

// EasyList's site is the canonical source, but the GitHub mirrors carry the same compiled
// files. Fanboy's Annoyance List already includes the EasyList Cookie List.
// `consent` marks the cookie-banner rules: the whole list, or the EasyList Cookie sections of a
// combined list. Those banners are parked rather than hidden while autoconsent answers them.
// `trusted` lists may use the scriptlets uBO reserves for its own lists (trusted-click-element,
// trusted-set-cookie, ...): uBO's lists, as in uBO. Any other list is parsed without that permission.
export const DEFAULT_LISTS = [
  { name: 'fanboy-annoyance.txt', url: 'https://raw.githubusercontent.com/easylist/easylist/gh-pages/fanboy-annoyance.txt', consent: 'easylist-cookie-sections' },
  { name: 'annoyances-cookies.txt', url: 'https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/annoyances-cookies.txt', consent: 'all', trusted: true },
  { name: 'annoyances-others.txt', url: 'https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/annoyances-others.txt', trusted: true },
];
// adblock-rust permission bit for trusted scriptlets (build.mjs sets it on uBO's requiresTrust ones).
export const TRUSTED = 1;
// uBO's scriptlets in the format adblock-rust's useResources() expects, built into dist/ by
// build.mjs from a pinned uBO release, so the app ships them instead of downloading them.
const SCRIPTLETS = new URL('../../dist/scriptlets.json', import.meta.url);

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

/** The cookie-banner part of a list (see DEFAULT_LISTS), or '' if it has none. */
export function consentPart(text, consent) {
  if (consent === 'all') return text;
  if (consent !== 'easylist-cookie-sections') return '';
  const out = [];
  let on = false;
  for (const line of text.split('\n')) {
    const m = /^! \*\*\* easylist:(\S+) \*\*\*/.exec(line);
    if (m) on = m[1].startsWith('easylist_cookie/');
    else if (on) out.push(line);
  }
  return out.join('\n');
}

export async function loadLists(dir, opts = {}) {
  const lists = [];
  for (const l of opts.lists ?? DEFAULT_LISTS) {
    const text = await fetchCached(dir, l, opts);
    lists.push({ name: l.name, text, consentText: consentPart(text, l.consent), trusted: !!l.trusted });
  }
  const { resources } = JSON.parse(await fs.readFile(SCRIPTLETS, 'utf8'));
  return { lists, resources };
}
