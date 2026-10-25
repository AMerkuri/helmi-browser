// End-to-end tests on local fixture pages: real content script, real filter lists, real
// policy, in Chromium with a phone viewport. The fixtures are hand-written imitations of common
// overlay patterns, not copies of real sites. The model is stubbed here (each fixture says
// what the model should answer); test/llm-eval.mjs measures the real model.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { launch, newContext, exposure } from '../harness/browser.mjs';
import { SereneEngine } from '../src/host/engine.mjs';
import { RulesEngine } from '../src/host/rules.mjs';
import { loadLists } from '../src/host/lists.mjs';
import { VerdictCache } from '../src/host/cache.mjs';
import { AutoconsentRules } from '../src/host/autoconsent.mjs';

const DIR = new URL('./fixtures/', import.meta.url).pathname;
const expectOf = (file) => JSON.parse(/<script type="application\/json" id="expect">(.*?)<\/script>/s.exec(fs.readFileSync(path.join(DIR, file), 'utf8'))[1]);

const FIXTURES = fs.readdirSync(DIR).filter((f) => f.endsWith('.html') && !expectOf(f).frameOnly).sort();

let server, base, browser, rules;
const autoconsent = new AutoconsentRules();

before(async () => {
  server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://x');
    const file = path.join(DIR, path.basename(url.pathname));
    if (!fs.existsSync(file)) { res.writeHead(404); return res.end(); }
    res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
    // ?slow=ms: headers first, body later, like a slow news site.
    const slow = Number(url.searchParams.get('slow')) || 0;
    if (slow) res.flushHeaders();
    setTimeout(() => fs.createReadStream(file).pipe(res), slow);
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  base = `http://127.0.0.1:${server.address().port}/`;
  rules = new RulesEngine(await loadLists(new URL('../.cache/lists/', import.meta.url).pathname));
  browser = await launch();
});

after(async () => { await browser?.close(); server?.close(); });

/** Stub model: answers with the fixture's expected label, and records that it was asked. */
function stubClassifier(calls) {
  return {
    async classify(f) {
      const exp = expectOf(path.basename(new URL(f.url).pathname));
      calls.push(f);
      return { ...(exp.llm ?? { label: 'content' }), ms: 0 };
    },
  };
}

async function runFixture(file, { cache = new VerdictCache(), query = '', withAutoconsent = true, hostDelayMs = 0 } = {}) {
  const exp = expectOf(file);
  const events = [], llmCalls = [];
  const engine = new SereneEngine({ rules, classifier: stubClassifier(llmCalls), cache, autoconsent: withAutoconsent ? autoconsent : null });
  const context = await newContext(browser, { engine, onEvent: (e) => events.push(e), probe: true, hostDelayMs });
  const page = await context.newPage();
  await page.goto(base + file + query);
  if (exp.tapFirst) { await page.waitForTimeout(300); await page.tap(exp.tapFirst); }
  await page.waitForTimeout(exp.waitMs ?? 2500);
  const state = await page.evaluate((exp) => {
    const shown = (sel) => {
      const el = document.querySelector(sel);
      if (!el) return false;
      const cs = getComputedStyle(el), r = el.getBoundingClientRect();
      return cs.display !== 'none' && cs.visibility !== 'hidden' && Number(cs.opacity) > 0.05 && r.height > 0;
    };
    const h = getComputedStyle(document.documentElement), b = getComputedStyle(document.body);
    return {
      gone: exp.gone ? !shown(exp.gone) : null,
      visible: (exp.visible || []).map((s) => [s, shown(s)]),
      clicked: window.__clicked || [],
      scrollable: h.overflow !== 'hidden' && b.overflow !== 'hidden' && b.position !== 'fixed',
    };
  }, exp);
  state.exposure = await exposure(page);
  await context.close();
  return { exp, state, events, llmCalls, cache };
}

for (const file of FIXTURES) {
  test(file, async () => {
    const { exp, state, events, llmCalls } = await runFixture(file);
    if (exp.gone) assert.ok(state.gone, `${exp.gone} should be gone`);
    for (const [sel, shown] of state.visible) assert.ok(shown, `${sel} should stay visible`);
    for (const c of exp.clicked || []) assert.ok(state.clicked.includes(c), `should click "${c}", clicked ${JSON.stringify(state.clicked)}`);
    for (const c of exp.notClicked || []) assert.ok(!state.clicked.includes(c), `must not click "${c}"`);
    if (exp.scroll) assert.ok(state.scrollable, 'page scrolling should be restored');
    if (exp.maxVisibleMs != null) {
      const shown = Math.max(0, ...state.exposure.filter((o) => o.sig.includes(exp.gone.slice(1))).map((o) => o.visibleMs));
      assert.ok(shown <= exp.maxVisibleMs, `${exp.gone} was on screen for ${shown} ms (at most ${exp.maxVisibleMs})`);
    }
    const acted = events.filter((e) => e.type === 'candidate' && e.verdict && e.verdict.action !== 'ignore');
    if (exp.via === 'rules') assert.equal(acted.length, 0, 'rules should handle it before the detector');
    if (exp.via === 'heuristic') assert.ok(acted.some((e) => e.verdict.source === 'heuristic'), `heuristics should act: ${JSON.stringify(events.filter((e) => e.type === 'candidate').map((e) => [e.features.sig, e.features.heuristic, e.verdict]))}`);
    const optedOut = events.some((e) => e.type === 'autoconsent' && e.event === 'optOutResult' && e.result);
    if (exp.via === 'autoconsent') assert.ok(optedOut && acted.length === 0, `autoconsent should answer it: ${JSON.stringify(events.filter((e) => e.type === 'autoconsent'))}`);
    if (exp.via === 'llm') assert.ok(llmCalls.length > 0 && acted.some((e) => e.verdict.source === 'llm'), 'model should be asked');
    if (!exp.gone) assert.equal(acted.length, 0, `nothing should be acted on: ${JSON.stringify(acted.map((e) => [e.features.sig, e.verdict]))}`);
  });
}

test('slow first bytes: the host replies before <html> exists, the detector still starts', async () => {
  const { state, events } = await runFixture('cookie-custom.html', { query: '?slow=1200' });
  assert.ok(state.gone, 'banner should be gone');
  assert.ok(events.some((e) => e.type === 'candidate' && e.verdict?.source === 'heuristic'), 'detector should have run');
});

test('a slow host reply: a consent banner in the page HTML stays hidden until the rules arrive', async () => {
  const { state } = await runFixture('cookie-in-html.html', { hostDelayMs: 800 });
  assert.ok(state.gone, 'banner should be gone');
  const shown = Math.max(0, ...state.exposure.filter((o) => o.sig.includes('cookiebanner')).map((o) => o.visibleMs));
  assert.ok(shown <= 100, `the banner was on screen for ${shown} ms before the host replied`);
});

test('without autoconsent a list-known banner in another language is only hidden', async () => {
  const { state, events } = await runFixture('cookie-onetrust-greek.html', { withAutoconsent: false });
  assert.ok(state.gone, 'banner should be hidden by the lists');
  assert.deepEqual(state.clicked, [], 'nothing pressed: the decline list has no Greek');
  assert.ok(!events.some((e) => e.type === 'autoconsent'));
});

test('second visit uses the site cache, not the model', async () => {
  const cache = new VerdictCache();
  const first = await runFixture('unknown-language.html', { cache });
  assert.equal(first.llmCalls.length, 1);
  const second = await runFixture('unknown-language.html', { cache });
  assert.equal(second.llmCalls.length, 0, 'model should not run again');
  assert.ok(second.state.gone);
});
