// The list catalog and refresh rules (mirrored by FilterListsTest.kt).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import os from 'node:os';
import { CATALOG, DEFAULT_LISTS, sourcesFor, customName, maxAgeMs, mirrorUrl, listTitle, ruleCount, loadLists } from '../src/host/lists.mjs';

test('defaults are the annoyance lists; ads, trackers and regional lists are opt-in', () => {
  assert.deepEqual(CATALOG.filter((g) => g.on).map((g) => g.id), ['fanboy-annoyances', 'ubo-cookies', 'ubo-annoyances', 'app-banners']);
  assert.ok(CATALOG.filter((g) => g.section !== 'annoyances').every((g) => !g.on));
  assert.deepEqual(sourcesFor(), DEFAULT_LISTS);
  assert.equal(new Set(CATALOG.flatMap((g) => g.lists.map((l) => l.name))).size, CATALOG.flatMap((g) => g.lists).length, 'file names are unique');
  // Only uBO's own lists may use trusted scriptlets.
  for (const l of CATALOG.flatMap((g) => g.lists)) assert.equal(!!l.trusted, l.url.includes('/uBlockOrigin/uAssets/'), l.name);
  assert.ok(CATALOG.find((g) => g.id === 'regional-et').langs.includes('et'));
});

test('custom lists come after the catalog and are never trusted', () => {
  const s = sourcesFor({ enabled: ['ads'], custom: ['https://example.com/list.txt'] });
  assert.deepEqual(s.map((l) => l.name), ['easylist.txt', 'ubo-filters.txt', 'ubo-unbreak.txt', 'ubo-quick-fixes.txt', customName('https://example.com/list.txt')]);
  assert.ok(!s.at(-1).trusted && s.at(-1).custom);
  assert.match(customName('https://example.com/list.txt'), /^custom-[0-9a-f]{12}\.txt$/);
});

test("a list's Expires header sets its refresh, between 12 hours and 4 days", () => {
  const h = 3600e3;
  assert.equal(maxAgeMs('! Title: x\n! Expires: 1 days (update frequency)\n'), 24 * h);
  assert.equal(maxAgeMs('! Expires: 8 hours'), 12 * h);
  assert.equal(maxAgeMs('! Expires: 7 days'), 96 * h);
  assert.equal(maxAgeMs('! Expires: 1 day'), 24 * h);
  assert.equal(maxAgeMs('##.ad'), 96 * h);
});

test('GitHub lists fall back to jsDelivr', () => {
  assert.equal(mirrorUrl('https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt'), 'https://cdn.jsdelivr.net/gh/uBlockOrigin/uAssets@master/filters/filters.txt');
  assert.equal(mirrorUrl('https://filters.adtidy.org/extension/ublock/filters/20.txt'), null);
});

test('titles and rule counts for custom lists', () => {
  const text = '[Adblock Plus 2.0]\n! Title: My list\n! Expires: 1 day\n\nexample.com##.promo\n||ads.example^\n';
  assert.equal(listTitle(text), 'My list');
  assert.equal(listTitle("! Title 🇬🇧: Dandelion Sprout's Nordic filters\n"), "Dandelion Sprout's Nordic filters");
  assert.equal(ruleCount(text), 2);
  assert.equal(ruleCount('<!doctype html><html>'), 1);
  assert.equal(ruleCount('! only comments\n\n'), 0);
});

test('My filters is one more untrusted list', async () => {
  const { lists } = await loadLists(os.tmpdir(), { lists: [], myFilters: 'example.com##.promo\n' });
  assert.deepEqual(lists, [{ name: 'my-filters', text: 'example.com##.promo\n', consentText: '', trusted: false }]);
  assert.equal((await loadLists(os.tmpdir(), { lists: [], myFilters: '  \n' })).lists.length, 0);
});
