import { test } from 'node:test';
import assert from 'node:assert/strict';
import { actionFor, isSafeTarget, preDecide, looksLikeChrome, fromModel } from '../src/host/policy.mjs';
import { buildPrompt, parseReply, scoreLabels } from '../src/host/classifier.mjs';
import { VerdictCache, siteOf } from '../src/host/cache.mjs';

const base = { sig: 'div.x', tag: 'div', role: '', ariaModal: false, tokens: '', text: 'We use cookies', textLen: 14, buttons: ['Accept all', 'Reject all'], hasEmail: false, hasPassword: false, hasVideo: false, iframeOrigins: [], coverage: 0.2, edge: 'bottom', scrollLocked: false, userInitiated: false, isBackdrop: false, looksLikeLayout: false };

test('only decline/close buttons count as safe click targets', () => {
  for (const s of ['Reject all', 'Nur notwendige Cookies', 'Continuer sans accepter', 'No thanks', '×', 'Not now', 'Rifiuta']) assert.ok(isSafeTarget(s), s);
  for (const s of ['Accept all', 'Allow', 'Subscribe', 'Open in app', 'Sign in', 'Got it', 'Alle akzeptieren', 'Yes, notify me', undefined]) assert.ok(!isSafeTarget(s), s);
  // Beyond Latin script: decline words count, agree words never do.
  for (const s of ['拒否する', '同意しない', '閉じる', '拒绝', '不同意', '거부', '동의하지 않음', 'Отклонить', 'Не сейчас', 'Reddet', 'Stäng']) assert.ok(isSafeTarget(s), s);
  for (const s of ['同意する', '同意して閉じる', '接受', '동의', 'Принять', 'Kabul et', 'Godkänn alla']) assert.ok(!isSafeTarget(s), s);
});

test('a model that picks "Accept" is overruled', () => {
  const v = actionFor('cookie_consent', 0, base);
  assert.equal(v.action, 'click');
  assert.equal(v.buttonLabel, 'Reject all');
  const noDecline = actionFor('cookie_consent', 0, { ...base, buttons: ['Accept all', 'Settings'] });
  assert.equal(noDecline.action, 'hide');
});

test('pay-or-consent walls: "reject and subscribe" is not a decline', () => {
  // corriere.it, 2026-10-24 crawl: the heuristics used to click "Rifiuta e abbonati".
  for (const s of ['Rifiuta e abbonati', 'Ablehnen und abonnieren', 'Refuser et s\'abonner', 'Rechazar y suscribirse']) assert.ok(!isSafeTarget(s), s);
  assert.equal(actionFor('cookie_consent', 2, { ...base, buttons: ['ACCETTA E CONTINUA', 'PREFERENZE', 'Rifiuta e abbonati'] }).action, 'hide');
});

test('site headers never go to the model', () => {
  const hdr = (o) => ({ ...base, edge: 'top', coverage: 0.07, textLen: 35, heuristic: { label: 'newsletter', score: 0.3, structure: 0, rejectIdx: -1 }, ...o });
  // stackoverflow and spiegel headers, healthline's sticky nav (all hidden by mistake on the first crawl)
  for (const f of [hdr({ tag: 'header', text: 'Skip to main content Log in Sign up' }), hdr({ tag: 'header', text: 'Anmelden Menü öffnen' }), hdr({ tokens: 'sticky-nav', text: 'Subscribe', heuristic: { label: 'newsletter', score: 0.9, structure: 0, rejectIdx: -1 } })]) {
    assert.ok(looksLikeChrome(f));
    const v = preDecide(f, { llmAvailable: true });
    assert.ok(!v.needsLlm && v.action === 'ignore', JSON.stringify(f.text));
  }
  // a thin cookie bar at the top is still handled
  const bar = hdr({ text: 'We use cookies. OK Reject', heuristic: { label: 'cookie_consent', score: 0.7, structure: 0, rejectIdx: 1 } });
  assert.equal(preDecide(bar, { llmAvailable: true }).action, 'click');
  assert.ok(!looksLikeChrome({ ...bar, edge: 'bottom' }));
});

test('bottom tab bars and link-heavy headers never go to the model', () => {
  const weak = { label: 'login_wall', score: 0.3, structure: 0, rejectIdx: -1 };
  // booking's bottom nav (the model called it a login wall), a tab bar, and a top bar of section links
  const navs = [
    { ...base, tag: 'nav', role: 'navigation', edge: 'bottom', coverage: 0.07, textLen: 20, text: 'Search Saved Sign in', buttons: ['Search', 'Saved', 'Sign in'], heuristic: weak },
    { ...base, tokens: 'tabbar', edge: 'bottom', coverage: 0.06, textLen: 30, text: 'Home Live Audio', buttons: ['Home', 'Live', 'Audio'], heuristic: weak },
    { ...base, edge: 'top', coverage: 0.1, textLen: 300, text: 'Se connecter Politique International', buttons: ['Menu', 'Notifications', 'Search', 'Se connecter', 'Politique', 'International'], heuristic: weak },
  ];
  for (const f of navs) {
    assert.ok(looksLikeChrome(f), f.text);
    assert.equal(preDecide(f, { llmAvailable: true }).action, 'ignore', f.text);
  }
  // an app banner at the bottom still goes to the model
  assert.ok(!looksLikeChrome({ ...base, tokens: 'smart-app-banner', edge: 'bottom', coverage: 0.1, textLen: 40, buttons: ['Open', 'Not now'] }));
});

test('paywall and login overlays are hidden, never clicked', () => {
  assert.equal(actionFor('paywall_teaser', 0, { ...base, buttons: ['Close'] }).action, 'hide');
  assert.equal(actionFor('login_wall', 0, { ...base, buttons: ['Close'] }).action, 'hide');
  assert.equal(actionFor('content', 1, base).action, 'ignore');
});

test('heuristic routing', () => {
  const h = (label, score, structure = 0.2, rejectIdx = 1) => ({ ...base, heuristic: { label, score, structure, rejectIdx } });
  assert.equal(preDecide(h('cookie_consent', 0.95), { llmAvailable: true }).source, 'heuristic');
  assert.ok(preDecide(h('cookie_consent', 0.5), { llmAvailable: true }).needsLlm);
  assert.equal(preDecide(h('cookie_consent', 0.7), { llmAvailable: false }).action, 'click');
  assert.equal(preDecide(h('cookie_consent', 0.5), { llmAvailable: false }).action, 'ignore');
  assert.ok(preDecide(h('content', 0, 0.35), { llmAvailable: true }).needsLlm, 'modal-shaped, no keywords → model');
  assert.equal(preDecide(h('content', 0, 0.1), { llmAvailable: true }).action, 'ignore');
  assert.equal(preDecide({ ...h('cookie_consent', 0.95), userInitiated: true }, { llmAvailable: true }).action, 'ignore');
});

test('prompt stays compact and replies are validated', () => {
  const p = buildPrompt({ ...base, text: 'x'.repeat(1000) });
  assert.ok(p.length < 600, `prompt is ${p.length} chars`);
  assert.match(p, /\[1\] Reject all/);
  assert.deepEqual(parseReply('{"label":"newsletter"}'), { label: 'newsletter' });
  assert.deepEqual(parseReply('<think></think>{"label":"survey","target":9}'), { label: 'survey' });
  assert.equal(parseReply('{"label":"banana"}'), null);
  assert.equal(parseReply('nope'), null);
});

test('model verdicts: the policy picks the button, unsure annoyance labels are left alone', () => {
  const v = fromModel({ label: 'cookie_consent', confidence: 0.97 }, base);
  assert.equal(v.action, 'click');
  assert.equal(v.buttonLabel, 'Reject all');
  assert.equal(fromModel({ label: 'cookie_consent', confidence: 0.5 }, base).action, 'ignore');
  assert.equal(fromModel({ label: 'cookie_consent', confidence: 0.5 }, base).source, 'llm-unsure');
  assert.equal(fromModel({ label: 'cookie_consent' }, base).action, 'click', 'generate mode has no confidence');
  assert.equal(fromModel({ label: 'content', confidence: 0.3 }, base).action, 'ignore');
});

test('single-pass label scores are a softmax over the labels', () => {
  const r = scoreLabels({ cookie_consent: 2, newsletter: 0, content: 0 });
  assert.equal(r.label, 'cookie_consent');
  assert.ok(Math.abs(r.confidence - Math.exp(2) / (Math.exp(2) + 2)) < 1e-9);
  assert.ok(Math.abs(Object.values(r.scores).reduce((a, b) => a + b, 0) - 1) < 1e-9);
  // The vocabulary's other tokens weigh as much as the labels together: half the mass is off-label.
  const w = 1 + 2 * Math.exp(-2); // sum of exp(logit - max) over the labels, max = 2
  assert.ok(Math.abs(scoreLabels({ cookie_consent: 2, newsletter: 0, content: 0 }, { total: 2 * w, max: 2 }).offLabel - 0.5) < 1e-9);
});

test('cache keys by registrable domain and emits CSS for hide verdicts', () => {
  assert.equal(siteOf('https://www.bbc.co.uk/news'), 'bbc.co.uk');
  const c = new VerdictCache();
  c.put('https://a.example.com/x', { sig: 'div.nl-modal', label: 'newsletter', action: 'hide', source: 'llm' });
  c.put('https://b.example.com/y', { sig: 'div > div', label: 'newsletter', action: 'hide', source: 'llm' });
  assert.equal(c.rulesFor('https://example.com/').length, 2);
  assert.equal(c.cssFor('https://example.com/'), 'div.nl-modal{display:none!important}');
  // With autoconsent on, a cookie banner is parked as a consent selector instead of hidden.
  c.put('https://example.com/', { sig: 'div#cmp', label: 'cookie_consent', action: 'hide', source: 'heuristic' });
  assert.equal(c.cssFor('https://example.com/', { parkCookies: true }), 'div.nl-modal{display:none!important}');
  assert.deepEqual(c.cookieSelectorsFor('https://example.com/'), ['div#cmp']);
  c.undo('https://example.com/', 'div.nl-modal', 'newsletter');
  assert.equal(c.rulesFor('https://example.com/')[0].action, 'ignore');
  assert.equal(c.cssFor('https://example.com/'), 'div#cmp{display:none!important}');
  assert.deepEqual(c.undone('https://example.com/').map((r) => [r.sig, r.was]), [['div.nl-modal', 'newsletter']]);
  // Hide again: the rule goes, so the next load judges the element afresh.
  c.remove('https://example.com/', 'div.nl-modal');
  assert.deepEqual(c.undone('https://example.com/'), []);
  assert.deepEqual(c.rulesFor('https://example.com/').map((r) => r.sig), ['div > div', 'div#cmp']);
});

test('consent tools\' own hosts are recognised, look-alikes are not', async () => {
  const { isConsentTool } = await import('../src/host/autoconsent.mjs');
  for (const u of ['https://cdn.cookielaw.org/scripttemplates/otSDKStub.js', 'https://consent.cookiebot.com/uc.js', 'https://cdn.privacy-mgmt.com/wrapperMessagingWithoutDetection.js', 'https://sdk.privacy-center.org/loader.js']) assert.ok(isConsentTool(u), u);
  for (const u of ['https://www.onetrust.com.evil.example/x.js', 'https://notcookielaw.org/x.js', 'https://cdn.example.com/cookielaw.org.js', 'not a url']) assert.ok(!isConsentTool(u), u);
});
