// Filter list catalog, download and on-disk cache. Ported to Kotlin as FilterLists.kt.
import fs from 'node:fs/promises';
import path from 'node:path';
import { createHash } from 'node:crypto';

// EasyList's site is the canonical source, but the GitHub mirrors carry the same compiled
// files. Fanboy's Annoyance List already includes the EasyList Cookie List.
// `consent` marks the cookie-banner rules: the whole list, or the EasyList Cookie sections of a
// combined list. Those banners are parked rather than hidden while autoconsent answers them.
// `trusted` lists may use the scriptlets uBO reserves for its own lists (trusted-click-element,
// trusted-set-cookie, ...): uBO's lists, as in uBO. Any other list is parsed without that permission.
const RAW = 'https://raw.githubusercontent.com/';
const UBO = RAW + 'uBlockOrigin/uAssets/master/filters/';
const ADGUARD = 'https://filters.adtidy.org/extension/ublock/filters/';

/**
 * The lists a user can switch on, as Settings › Filter lists shows them. `on`: on by default.
 * `section`: annoyances (Serene's own job), ads (ads and trackers), regional (ad lists for one
 * region, offered with "Block ads" and suggested by the phone's languages, `langs`).
 */
export const CATALOG = [
  { id: 'fanboy-annoyances', section: 'annoyances', on: true, title: "Fanboy's Annoyances", summary: 'Cookie notices, newsletters, social widgets, push prompts', lists: [
    { name: 'fanboy-annoyance.txt', url: RAW + 'easylist/easylist/gh-pages/fanboy-annoyance.txt', consent: 'easylist-cookie-sections' },
  ] },
  { id: 'ubo-cookies', section: 'annoyances', on: true, title: 'uBO Cookie Notices', summary: 'Cookie notices, often answered with "reject"', lists: [
    { name: 'annoyances-cookies.txt', url: UBO + 'annoyances-cookies.txt', consent: 'all', trusted: true },
  ] },
  { id: 'ubo-annoyances', section: 'annoyances', on: true, title: 'uBO Other Annoyances', summary: 'Popups, overlays, sticky nags', lists: [
    { name: 'annoyances-others.txt', url: UBO + 'annoyances-others.txt', trusted: true },
  ] },
  // "Open in app" bars and interstitials: AdGuard's list (its uBO-syntax build) and Fanboy's.
  { id: 'app-banners', section: 'annoyances', on: true, title: 'Mobile app banners', summary: '"Open in app" bars and interstitials (AdGuard, Fanboy)', lists: [
    { name: 'adguard-mobile-app-banners.txt', url: ADGUARD + '20.txt' },
    { name: 'fanboy-mobile-notifications.txt', url: 'https://secure.fanboy.co.nz/fanboy-mobile-notifications.txt' },
  ] },
  { id: 'more-popups', section: 'annoyances', on: false, title: 'More popups and widgets', summary: 'AdGuard Popups, Other Annoyances and Widgets: catches more, and hides more by mistake', lists: [
    { name: 'adguard-popups.txt', url: ADGUARD + '19.txt' },
    { name: 'adguard-other-annoyances.txt', url: ADGUARD + '21.txt' },
    { name: 'adguard-widgets.txt', url: ADGUARD + '22.txt' },
  ] },
  { id: 'trackers', section: 'ads', on: false, title: 'Block trackers', summary: 'EasyPrivacy and uBO Privacy: blocks requests, hides nothing', lists: [
    { name: 'easyprivacy.txt', url: RAW + 'easylist/easylist/gh-pages/easyprivacy.txt' },
    { name: 'ubo-privacy.txt', url: UBO + 'privacy.txt', trusted: true },
  ] },
  // uBO's own list and Quick fixes carry the ad-blocker-wall fixes; Unbreak undoes EasyList's breakage.
  { id: 'ads', section: 'ads', on: false, title: 'Block ads', summary: 'EasyList and uBO filters, with their fixes for ad-blocker walls', lists: [
    { name: 'easylist.txt', url: RAW + 'easylist/easylist/gh-pages/easylist.txt' },
    { name: 'ubo-filters.txt', url: UBO + 'filters.txt', trusted: true },
    { name: 'ubo-unbreak.txt', url: UBO + 'unbreak.txt', trusted: true },
    { name: 'ubo-quick-fixes.txt', url: UBO + 'quick-fixes.txt', trusted: true },
  ] },
  ...[
    ['et', 'Estonian', ['et'], 'https://ubo-et.lepik.io/list.txt'],
    ['fi', 'Finnish', ['fi'], RAW + 'finnish-easylist-addition/finnish-easylist-addition/gh-pages/Finland_adb.txt'],
    ['lv', 'Latvian', ['lv'], RAW + 'Latvian-List/adblock-latvian/master/lists/latvian-list.txt'],
    ['lt', 'Lithuanian', ['lt'], RAW + 'EasyList-Lithuania/easylist_lithuania/master/easylistlithuania.txt'],
    ['nordic', 'Nordic', ['nb', 'nn', 'no', 'da', 'is'], RAW + 'DandelionSprout/adfilt/master/NorwegianList.txt'],
    ['sv', 'Swedish', ['sv'], RAW + 'lassekongo83/Frellwits-filter-lists/master/Frellwits-Swedish-Filter.txt'],
    ['de', 'German', ['de'], 'https://easylist-downloads.adblockplus.org/easylistgermany.txt'],
    ['pl', 'Polish', ['pl'], RAW + 'MajkiIT/polish-ads-filter/master/polish-adblock-filters/adblock.txt'],
    ['ru', 'Russian and Ukrainian', ['ru', 'uk', 'be'], 'https://easylist-downloads.adblockplus.org/advblock.txt'],
    ['fr', 'French', ['fr'], ADGUARD + '16.txt'],
    ['es', 'Spanish', ['es'], 'https://easylist-downloads.adblockplus.org/easylistspanish.txt'],
    ['it', 'Italian', ['it'], 'https://easylist-downloads.adblockplus.org/easylistitaly.txt'],
    ['nl', 'Dutch', ['nl'], ADGUARD + '8.txt'],
  ].map(([code, title, langs, url]) => ({ id: 'regional-' + code, section: 'regional', on: false, title, summary: `Ads on ${title} sites`, langs, lists: [{ name: `regional-${code}.txt`, url }] })),
];

export const DEFAULT_LISTS = CATALOG.filter((g) => g.on).flatMap((g) => g.lists);

/** A custom list's file name: stable for its URL. */
export function customName(url) {
  return 'custom-' + createHash('sha1').update(url).digest('hex').slice(0, 12) + '.txt';
}

/**
 * The lists to load: the catalog entries in `enabled` (ids; default the catalog's defaults) and
 * the user's own list URLs, which are never trusted. `myFilters` (the user's own rules) is not
 * a download: loadLists adds it as one more untrusted list.
 */
export function sourcesFor({ enabled = null, custom = [] } = {}) {
  const on = enabled ? new Set(enabled) : new Set(CATALOG.filter((g) => g.on).map((g) => g.id));
  return [
    ...CATALOG.filter((g) => on.has(g.id)).flatMap((g) => g.lists),
    ...custom.map((url) => ({ name: customName(url), url, custom: true })),
  ];
}

// adblock-rust permission bit for trusted scriptlets (build.mjs sets it on uBO's requiresTrust ones).
export const TRUSTED = 1;
// uBO's scriptlets in the format adblock-rust's useResources() expects, built into dist/ by
// build.mjs from a pinned uBO release, so the app ships them instead of downloading them.
const SCRIPTLETS = new URL('../../dist/scriptlets.json', import.meta.url);

// How long a downloaded list is used before it is fetched again: its `! Expires:` header (uBO's
// Quick fixes says 8 hours, Lithuania's 7 days), kept between 12 hours and 4 days.
const MIN_AGE_MS = 12 * 3600 * 1000;
const MAX_AGE_MS = 4 * 24 * 3600 * 1000;

/** The refresh interval a list asks for in its header, within MIN_AGE_MS..MAX_AGE_MS. */
export function maxAgeMs(text) {
  const m = /^! *Expires: *(\d+) *(hour|day)/im.exec(text.slice(0, 4000));
  if (!m) return MAX_AGE_MS;
  const ms = Number(m[1]) * (m[2].toLowerCase() === 'hour' ? 3600e3 : 24 * 3600e3);
  return Math.min(MAX_AGE_MS, Math.max(MIN_AGE_MS, ms));
}

/** jsDelivr's copy of a raw.githubusercontent.com file, tried when GitHub fails; else null. */
export function mirrorUrl(url) {
  const m = /^https:\/\/raw\.githubusercontent\.com\/([^/]+)\/([^/]+)\/([^/]+)\/(.+)$/.exec(url);
  return m ? `https://cdn.jsdelivr.net/gh/${m[1]}/${m[2]}@${m[3]}/${m[4]}` : null;
}

/** A list's `! Title:` header, or null. */
export function listTitle(text) {
  return /^! *Title[^:]*: *(.+)$/m.exec(text.slice(0, 4000))?.[1].trim() ?? null;
}

/** Lines that are rules: not comments, headers or blank. */
export function ruleCount(text) {
  let n = 0;
  for (const line of text.split('\n')) {
    const t = line.trim();
    if (t && !t.startsWith('!') && !t.startsWith('[')) n++;
  }
  return n;
}

async function download(url) {
  let lastErr;
  for (const u of [url, mirrorUrl(url)].filter(Boolean)) {
    try {
      const res = await fetch(u);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      return await res.text();
    } catch (err) { lastErr = err; }
  }
  throw lastErr;
}

async function fetchCached(dir, { name, url }, { offline = false } = {}) {
  const file = path.join(dir, name);
  let stat = null, old = null;
  try { stat = await fs.stat(file); old = await fs.readFile(file, 'utf8'); } catch {}
  if (old != null && (offline || Date.now() - stat.mtimeMs < maxAgeMs(old))) return old;
  try {
    const text = await download(url);
    await fs.mkdir(dir, { recursive: true });
    await fs.writeFile(file, text);
    return text;
  } catch (err) {
    if (old != null) return old; // stale copy beats no rules
    throw new Error(`could not fetch ${url}: ${err.message}`);
  }
}

/** The cookie-banner part of a list (see CATALOG), or '' if it has none. */
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

/**
 * Downloads (or reuses) `opts.lists` (default DEFAULT_LISTS) and returns them with the shipped
 * scriptlets. A custom list that fails or has no rules is left out rather than failing the rest;
 * `opts.myFilters` is added as one more untrusted list.
 */
export async function loadLists(dir, opts = {}) {
  const lists = [];
  for (const l of opts.lists ?? DEFAULT_LISTS) {
    let text;
    try { text = await fetchCached(dir, l, opts); } catch (err) { if (l.custom) continue; throw err; }
    if (l.custom && ruleCount(text) === 0) continue;
    lists.push({ name: l.name, text, consentText: consentPart(text, l.consent), trusted: !!l.trusted });
  }
  if (opts.myFilters?.trim()) lists.push({ name: 'my-filters', text: opts.myFilters, consentText: '', trusted: false });
  const { resources } = JSON.parse(await fs.readFile(SCRIPTLETS, 'utf8'));
  return { lists, resources };
}
