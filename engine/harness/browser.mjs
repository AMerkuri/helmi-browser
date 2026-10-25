// Playwright stand-in for the WebView integration:
//   addInitScript     ≈ WebViewCompat.addDocumentStartJavaScript (all frames, before page JS)
//   exposeBinding     ≈ WebViewCompat.addWebMessageListener (SereneBridge.postMessage / onmessage)
//   context.route     ≈ WebViewClient.shouldInterceptRequest
import fs from 'node:fs';
import { chromium, devices } from 'playwright';

const CONTENT_JS = fs.readFileSync(new URL('../src/content/serene.js', import.meta.url), 'utf8');
// Built by `npm run build`; serene.js starts it when the host's init reply carries rules.
const AUTOCONSENT_JS = fs.readFileSync(new URL('../dist/autoconsent.js', import.meta.url), 'utf8');
const SHIM = `
  window.SereneBridge = { postMessage: (s) => window.__sereneHost(s), onmessage: null };
  window.__sereneDeliver = (s) => { const b = window.SereneBridge; if (b && b.onmessage) b.onmessage({ data: s }); };
`;

// Harness-only exposure probe (top frame): every 100 ms it samples the same viewport points as
// the detector and records, per fixed/sticky overlay, how long it was actually visible. That is
// what the user sees of an overlay before the engine (or the page) takes it away.
const PROBE = `(() => {
  if (window !== window.top) return;
  const recs = new Map(); // el -> { first, last, visibleMs }
  window.__sereneProbe = recs;
  const shown = (el) => {
    if (!el.isConnected) return false;
    for (let n = el; n && n.nodeType === 1; n = n.parentElement || (n.getRootNode && n.getRootNode().host) || null) {
      const cs = getComputedStyle(n);
      if (cs.display === 'none' || cs.visibility === 'hidden' || Number(cs.opacity) < 0.05) return false;
    }
    const r = el.getBoundingClientRect();
    return r.width > 1 && r.height > 1;
  };
  let prev = 0;
  const tick = () => {
    if (!document.body) return;
    const now = performance.now(), dt = prev ? now - prev : 0;
    prev = now;
    for (const fx of [0.15, 0.5, 0.85]) for (const fy of [0.03, 0.2, 0.4, 0.6, 0.8, 0.97]) {
      let stack;
      try { stack = document.elementsFromPoint(innerWidth * fx, innerHeight * fy); } catch { continue; }
      for (const el of stack.slice(0, 6)) {
        if (el === document.documentElement || el === document.body) break;
        let root = null;
        for (let n = el; n && n !== document.body && n !== document.documentElement; n = n.parentElement || (n.getRootNode && n.getRootNode().host) || null) {
          if (n.nodeType === 1) { const p = getComputedStyle(n).position; if (p === 'fixed' || p === 'sticky') root = n; }
        }
        if (root) { if (!recs.has(root)) recs.set(root, { first: null, last: null, visibleMs: 0 }); break; }
      }
    }
    for (const [el, r] of recs) {
      if (!shown(el)) continue;
      if (r.first == null) {
        r.first = now;
        try { const f = window.__serene && window.__serene.extract(el); if (f) r.f = { sig: f.sig, edge: f.edge, coverage: f.coverage, label: f.heuristic.label, score: f.heuristic.score, text: f.text.slice(0, 90) }; } catch {}
      } else r.visibleMs += Math.min(dt, 250);
      r.last = now;
    }
  };
  setInterval(tick, 100);
})();`;

// Consent tools' own hosts. With allowConsentTools the lists may not block these, so the
// consent tool shows its banner and autoconsent can answer it with a real opt-out (crawl
// experiment, `--allow-consent-tools`).
export const CONSENT_TOOL_HOSTS = /(^|\.)(cookielaw\.org|onetrust\.com|cookiebot\.(com|eu)|usercentrics\.(eu|com)|didomi\.io|privacy-mgmt\.com|sp-prod\.net|consensu\.org|quantcast\.com|inmobi\.com|trustarc\.com|truste\.com|consentmanager\.(net|de)|iubenda\.com|cookieyes\.com|osano\.com|termly\.io|cookie-script\.com|axeptio\.eu|cookiefirst\.com|privacymanager\.io|fundingchoicesmessages\.google\.com|sirdata\.(com|net)|ketchcdn\.com|clickio\.com|uniconsent\.com)$/;

const TYPE_MAP = { document: 'sub_frame', xhr: 'xmlhttprequest', fetch: 'xmlhttprequest', eventsource: 'other', websocket: 'websocket', manifest: 'other', texttrack: 'media' };

export async function launch({ headless = true } = {}) {
  const opts = { headless };
  if (process.env.SERENE_CHROMIUM) opts.executablePath = process.env.SERENE_CHROMIUM;
  return chromium.launch(opts);
}

/** New context emulating a phone. engine = SereneEngine or null (plain browser). */
export async function newContext(browser, { engine = null, onEvent = () => {}, locale = 'en-GB', probe = false, allowConsentTools = false } = {}) {
  const context = await browser.newContext({ ...devices['Pixel 7'], locale, ignoreHTTPSErrors: false });
  if (probe) await context.addInitScript({ content: PROBE });
  if (!engine) return context;
  const sessions = new WeakMap(); // page -> PageSession

  const sessionFor = (page) => {
    let s = sessions.get(page);
    if (!s) { s = engine.newPage(onEvent); sessions.set(page, s); }
    return s;
  };
  const frameCtx = (frame) => ({
    url: frame.url(),
    isTop: frame === frame.page().mainFrame(),
    send: (obj) => frame.evaluate((s) => !!(window.__sereneDeliver && (window.__sereneDeliver(s), 1)), JSON.stringify(obj)).then((ok) => { if (process.env.SERENE_DEBUG) console.log('send', obj.type, frame.url().slice(0, 60), ok); }).catch((e) => { if (process.env.SERENE_DEBUG) console.log('send FAIL', obj.type, frame.url().slice(0, 60), e.message.split('\n')[0]); }),
  });

  await context.exposeBinding('__sereneHost', async (source, json) => {
    const msg = JSON.parse(json);
    const isTop = source.frame === source.page.mainFrame();
    // A new document in the main frame is a new navigation: fresh page session.
    if (msg.type === 'hello' && isTop) sessions.set(source.page, engine.newPage(onEvent));
    await sessionFor(source.page).onMessage(msg, frameCtx(source.frame));
  });
  await context.addInitScript({ content: SHIM + AUTOCONSENT_JS + '\n' + CONTENT_JS });

  if (engine.rules) {
    await context.route('**/*', (route) => {
      const req = route.request();
      let page = null;
      try { page = req.frame().page(); } catch {}
      const isMainDoc = req.isNavigationRequest() && page && req.frame() === page.mainFrame();
      if (!page || isMainDoc || !/^https?:/.test(req.url())) return route.continue();
      const type = TYPE_MAP[req.resourceType()] || req.resourceType();
      const source = page.mainFrame().url();
      if (allowConsentTools) {
        let host = '';
        try { host = new URL(req.url()).hostname; } catch {}
        if (CONSENT_TOOL_HOSTS.test(host)) return route.continue();
      }
      return sessionFor(page).shouldBlock(req.url(), type, source) ? route.abort('blockedbyclient') : route.continue();
    });
  }
  return context;
}

/** Overlays still on screen, measured with the same detector in observe-only mode. */
export async function residualOverlays(page) {
  return page.evaluate(() => {
    const s = window.__serene;
    if (!s) return null;
    const out = [];
    const seen = new Set();
    const pts = [];
    for (const fx of [0.15, 0.5, 0.85]) for (const fy of [0.03, 0.2, 0.4, 0.6, 0.8, 0.97]) pts.push([innerWidth * fx, innerHeight * fy]);
    for (const [x, y] of pts) {
      for (const el of document.elementsFromPoint(x, y).slice(0, 6)) {
        if (el === document.documentElement || el === document.body) break;
        let root = null;
        for (let n = el; n && n !== document.body && n !== document.documentElement; n = n.parentElement || n.getRootNode().host) {
          const p = getComputedStyle(n).position;
          if (p === 'fixed' || p === 'sticky') root = n;
        }
        if (root) { if (!seen.has(root)) { seen.add(root); const f = s.extract(root); if (f) { delete f._buttons; delete f._iframes; out.push(f); } } break; }
      }
    }
    const h = getComputedStyle(document.documentElement), b = getComputedStyle(document.body);
    const locked = h.overflow === 'hidden' || h.overflowY === 'hidden' || b.overflow === 'hidden' || b.overflowY === 'hidden' || b.position === 'fixed';
    return { overlays: out, scrollLocked: locked, scanMs: s.state.scanMs, started: s.state.started, href: location.href };
  });
}

/**
 * What the exposure probe saw: every overlay that was on screen at some point, when it first
 * showed (ms after navigation start), how long it stayed visible, and the engine's candidate id
 * for it, if the detector reported it.
 */
export async function exposure(page) {
  return page.evaluate(() => {
    const recs = window.__sereneProbe, s = window.__serene;
    if (!recs) return null;
    const ids = new Map();
    if (s) for (const [id, c] of s.state.candidates) ids.set(c.el, id);
    const out = [];
    for (const [el, r] of recs) {
      if (r.first == null) continue;
      const f = r.f ?? {};
      out.push({
        candidateId: ids.get(el) ?? null,
        sig: f.sig ?? el.localName, edge: f.edge ?? null, coverage: f.coverage ?? null,
        heuristic: f.label ? { label: f.label, score: f.score } : null,
        text: f.text ?? '',
        firstMs: Math.round(r.first), visibleMs: Math.round(r.visibleMs), visibleNow: performance.now() - r.last < 250,
      });
    }
    return out;
  });
}
