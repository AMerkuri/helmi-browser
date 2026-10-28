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

let server, base, browser, lists, rules;
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
  lists = await loadLists(new URL('../.cache/lists/', import.meta.url).pathname);
  rules = new RulesEngine(lists);
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

// A fixture's own `rules` are added to the lists as one more list, trusted like uBO's unless
// `trusted` is false.
// `then(page)` runs after the wait, before the page is read; what it returns is state.then.
// `fixtureRulesText(exp)` can change the fixture's own rules.
async function runFixture(file, { cache = new VerdictCache(), query = '', withAutoconsent = true, hostDelayMs = 0, trusted = true, then = null, fixtureRulesText = exp => exp.rules } = {}) {
  const exp = expectOf(file);
  const events = [], llmCalls = [];
  const text = fixtureRulesText(exp);
  const fixtureRules = text ? new RulesEngine({ ...lists, lists: [...lists.lists, { name: file, text, trusted }] }) : rules;
  const engine = new SereneEngine({ rules: fixtureRules, classifier: stubClassifier(llmCalls), cache, autoconsent: withAutoconsent ? autoconsent : null });
  const context = await newContext(browser, { engine, onEvent: (e) => events.push(e), probe: true, hostDelayMs });
  const page = await context.newPage();
  await page.goto(base + file + query);
  if (exp.tapFirst) { await page.waitForTimeout(300); await page.tap(exp.tapFirst); }
  // taps: ms after load at which the user touches the bottom of the screen (trying to scroll).
  const t0 = Date.now();
  const until = (ms) => page.waitForTimeout(Math.max(0, ms - (Date.now() - t0)));
  for (const ms of exp.taps || []) { await until(ms); await page.touchscreen.tap(20, page.viewportSize().height - 20); }
  await until(exp.waitMs ?? 2500);
  const thenOut = then ? await then(page) : null;
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
      touchPrevented: window.__touchPrevented,
      scrollable: ![h.overflow, h.overflowY, b.overflow, b.overflowY].includes('hidden') && b.position !== 'fixed',
    };
  }, exp);
  state.exposure = await exposure(page);
  state.then = thenOut;
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
    // touchable: the fixture records whether the page cancelled the last tap (a focus trap).
    if (exp.touchable) assert.equal(state.touchPrevented, false, 'the last tap should reach the page');
    if (exp.maxVisibleMs != null) {
      const shown = Math.max(0, ...state.exposure.filter((o) => o.sig.includes(exp.gone.slice(1))).map((o) => o.visibleMs));
      assert.ok(shown <= exp.maxVisibleMs, `${exp.gone} was on screen for ${shown} ms (at most ${exp.maxVisibleMs})`);
    }
    const acted = events.filter((e) => e.type === 'candidate' && e.verdict && e.verdict.action !== 'ignore');
    if (exp.via === 'rules') assert.equal(acted.length, 0, 'rules should handle it before the detector');
    // The start page's totals count a banner the lists hid only from this report.
    if (exp.via === 'rules' && file.startsWith('cookie')) assert.ok(events.some((e) => e.type === 'listHit' && e.label === 'cookie_consent'), 'a list-hidden cookie banner should be reported');
    if (!exp.gone) assert.ok(!events.some((e) => e.type === 'listHit'), 'nothing should be reported as a list-hidden banner');
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

test('a trusted scriptlet from an untrusted list does nothing', async () => {
  const { state } = await runFixture('scriptlet-click.html', { trusted: false });
  assert.deepEqual(state.clicked, [], 'trusted-click-element needs a trusted list');
  assert.ok(!state.gone, 'the bar should stay');
});

test('second visit uses the site cache, not the model', async () => {
  const cache = new VerdictCache();
  const first = await runFixture('unknown-language.html', { cache });
  assert.equal(first.llmCalls.length, 1);
  const second = await runFixture('unknown-language.html', { cache });
  assert.equal(second.llmCalls.length, 0, 'model should not run again');
  assert.ok(second.state.gone);
});

// On a reload the cached CSS hides the dialog before the detector ever sees it (techspot, #24).
test('a cached hide: on the next visit the dimmed wrapper still goes while the user touches it', async () => {
  const cache = new VerdictCache();
  await runFixture('cookie-dim-wrapper-lock.html', { cache });
  const { state } = await runFixture('cookie-dim-wrapper-lock.html', { cache });
  assert.ok(state.gone, '.pv-shell should be gone');
  assert.ok(state.scrollable, 'page scrolling should be restored');
  const shown = Math.max(0, ...state.exposure.filter((o) => o.sig.includes('pv-shell')).map((o) => o.visibleMs));
  assert.ok(shown <= 600, `.pv-shell was on screen for ${shown} ms (at most 600)`);
});

test('a cached hide: on the next visit a focus trap still lets go', async () => {
  const cache = new VerdictCache();
  await runFixture('cookie-focus-trap-late.html', { cache });
  const { state } = await runFixture('cookie-focus-trap-late.html', { cache });
  assert.ok(state.gone, '#pv-ui should be gone');
  assert.ok(state.clicked.includes('story'), `should click "story", clicked ${JSON.stringify(state.clicked)}`);
  assert.equal(state.touchPrevented, false, 'the last tap should reach the page');
});

// lhv.ee (#48): the model once called an Estonian cookie dialog content, and that ignore was cached.
test('a cached ignore goes back to the host once the heuristics know the overlay, an undo does not', async () => {
  const file = 'cookie-estonian.html', sig = 'div#et-cookie-dialog';
  const stale = new VerdictCache();
  stale.put(base + file, { sig, label: 'content', action: 'ignore', source: 'llm' });
  const again = await runFixture(file, { cache: stale });
  assert.ok(again.state.gone, '#et-cookie-dialog should be gone');
  assert.deepEqual(again.state.clicked, ['keeldu']);
  assert.equal(stale.rulesFor(base + file).find((r) => r.sig === sig).action, 'click', 'the new verdict replaces the cached one');
  const undone = new VerdictCache();
  undone.undo(base + file, sig);
  const kept = await runFixture(file, { cache: undone });
  assert.ok(!kept.state.gone, 'the user put it back: leave it');
  assert.deepEqual(kept.state.clicked, []);
});

test('a cached cookie banner is parked, not hidden, so autoconsent still rejects it on the next visit', async () => {
  const cache = new VerdictCache();
  await runFixture('cookie-quantcast-style.html', { cache });
  const { state } = await runFixture('cookie-quantcast-style.html', { cache });
  assert.ok(state.gone, '#qc-cmp2-ui should be gone');
  assert.deepEqual(state.clicked, ['reject']);
});

// swedbank.ee: the banner's own "Manage cookies" link in the footer reopens the dialog Serene hid,
// on a modal system the site's other dialogs share. The list's scriptlet answers the banner.
test('a hidden cookie banner opens again from the site\'s "Manage cookies", and other dialogs still open', async () => {
  const file = 'cookie-reopen.html', cookie = '#x-modal-uid-38412', legal = '#x-modal-uid-55107';
  const shown = (page, sel) => page.evaluate((sel) => { const el = document.querySelector(sel), cs = getComputedStyle(el); return cs.display !== 'none' && Number(cs.opacity) > 0.05 && el.getBoundingClientRect().height > 0; }, sel);
  const openBoth = async (page) => {
    const out = {};
    await page.tap('#legal-link');
    await page.waitForTimeout(400);
    out.legal = await shown(page, legal);
    await page.evaluate((sel) => closeModal(document.querySelector(sel)), legal);
    await page.waitForTimeout(1600); // past the tap's window
    await page.tap('#cookie-link');
    await page.waitForTimeout(400);
    out.cookie = await shown(page, cookie);
    await page.waitForTimeout(2000); // later scans leave the user's dialog alone
    out.cookieLater = await shown(page, cookie);
    return out;
  };
  const cache = new VerdictCache();
  // 'late': the scriptlet presses the button after Serene has hidden the banner with an inline style.
  const late = (exp) => exp.rules.replace(', 1000)', ', 1800)');
  for (const visit of ['first', 'cached', 'late']) {
    const { state, events } = await runFixture(file, visit === 'late' ? { fixtureRulesText: late, then: openBoth } : { cache, then: openBoth });
    assert.deepEqual(state.clicked, ['necessary'], `${visit}: the list's scriptlet answers the banner`);
    assert.ok(events.some((e) => e.type === 'listConsent' && e.button === 'Accept only necessary'), `${visit}: the scriptlet's answer is reported`);
    assert.ok(state.then.legal, `${visit}: the site's other dialogs still open`);
    assert.ok(state.then.cookie && state.then.cookieLater, `${visit}: "Manage cookies" shows the cookie dialog`);
  }
  // Remembered without its state classes, which every open dialog on the site has.
  assert.deepEqual(cache.rulesFor(base + file).map((r) => [r.sig, r.action]), [['x-modal.zz-cookie-consent', 'hide']]);
});
