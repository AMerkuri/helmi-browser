// The rules layer with the real lists and the scriptlets built into dist/ (no browser).
import { test, before } from 'node:test';
import assert from 'node:assert/strict';
import { RulesEngine } from '../src/host/rules.mjs';
import { loadLists } from '../src/host/lists.mjs';

let lists, rules;
before(async () => {
  lists = await loadLists(new URL('../.cache/lists/', import.meta.url).pathname);
  rules = new RulesEngine(lists);
});

/** The first site a list answers with `##+js(<name>, ...)`. */
function siteWith(listName, scriptlet) {
  const text = lists.lists.find((l) => l.name === listName).text;
  const re = new RegExp(`^([a-z0-9.-]+\\.[a-z]+)(?:,[^#]*)?##\\+js\\(${scriptlet},`, 'm');
  return re.exec(text)[1];
}

test("uBO Cookie Notices' scriptlets are injected", () => {
  const site = siteWith('annoyances-cookies.txt', 'trusted-click-element');
  const { script } = rules.cosmetic(`https://${site}/`);
  assert.match(script, /trustedClickElement\(/, `no trusted-click-element on ${site}`);
  assert.match(script, /function safeSelf\(/, 'its helpers come with it');
});

test('untrusted scriptlets work for any list, trusted ones only for trusted lists', () => {
  const text = 'example.com##+js(set, foo, false)\nexample.com##+js(trusted-click-element, #reject)';
  const script = (trusted) => new RulesEngine({ ...lists, lists: [{ name: 'x', text, trusted }] }).cosmetic('https://example.com/').script;
  assert.match(script(false), /setConstant\("foo", "false"\)/);
  assert.doesNotMatch(script(false), /trustedClickElement\(/);
  assert.match(script(true), /trustedClickElement\("#reject"\)/);
});

test('every scriptlet the lists name exists', () => {
  const names = new Set(lists.resources.flatMap((r) => [r.name, ...r.aliases]));
  const missing = new Set();
  for (const l of lists.lists) {
    for (const [, n] of l.text.matchAll(/#\+js\(([^,)\s]+)/g)) {
      const name = n.endsWith('.js') ? n : n + '.js';
      if (!names.has(name)) missing.add(`${n} (${l.name})`);
    }
  }
  assert.deepEqual([...missing], []);
});
