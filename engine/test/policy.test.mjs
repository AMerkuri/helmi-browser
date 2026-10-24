import { test } from 'node:test';
import assert from 'node:assert/strict';
import { actionFor, isSafeTarget, preDecide } from '../src/host/policy.mjs';
import { buildPrompt, parseReply } from '../src/host/classifier.mjs';
import { VerdictCache, siteOf } from '../src/host/cache.mjs';

const base = { sig: 'div.x', tag: 'div', role: '', ariaModal: false, tokens: '', text: 'We use cookies', textLen: 14, buttons: ['Accept all', 'Reject all'], hasEmail: false, hasPassword: false, hasVideo: false, iframeOrigins: [], coverage: 0.2, edge: 'bottom', scrollLocked: false, userInitiated: false, isBackdrop: false, looksLikeLayout: false };

test('only decline/close buttons count as safe click targets', () => {
  for (const s of ['Reject all', 'Nur notwendige Cookies', 'Continuer sans accepter', 'No thanks', '×', 'Not now', 'Rifiuta']) assert.ok(isSafeTarget(s), s);
  for (const s of ['Accept all', 'Allow', 'Subscribe', 'Open in app', 'Sign in', 'Got it', 'Alle akzeptieren', 'Yes, notify me', undefined]) assert.ok(!isSafeTarget(s), s);
});

test('a model that picks "Accept" is overruled', () => {
  const v = actionFor('cookie_consent', 0, base);
  assert.equal(v.action, 'click');
  assert.equal(v.buttonLabel, 'Reject all');
  const noDecline = actionFor('cookie_consent', 0, { ...base, buttons: ['Accept all', 'Settings'] });
  assert.equal(noDecline.action, 'hide');
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
  assert.deepEqual(parseReply('{"label":"newsletter","target":1}', 2), { label: 'newsletter', target: 1 });
  assert.deepEqual(parseReply('<think></think>{"label":"survey","target":9}', 2), { label: 'survey', target: -1 });
  assert.equal(parseReply('{"label":"banana","target":0}', 2), null);
  assert.equal(parseReply('nope', 2), null);
});

test('cache keys by registrable domain and emits CSS for hide verdicts', () => {
  assert.equal(siteOf('https://www.bbc.co.uk/news'), 'bbc.co.uk');
  const c = new VerdictCache();
  c.put('https://a.example.com/x', { sig: 'div.nl-modal', label: 'newsletter', action: 'hide', source: 'llm' });
  c.put('https://b.example.com/y', { sig: 'div > div', label: 'newsletter', action: 'hide', source: 'llm' });
  assert.equal(c.rulesFor('https://example.com/').length, 2);
  assert.equal(c.cssFor('https://example.com/'), 'div.nl-modal{display:none!important}');
  c.undo('https://example.com/', 'div.nl-modal');
  assert.equal(c.rulesFor('https://example.com/')[0].action, 'ignore');
});
