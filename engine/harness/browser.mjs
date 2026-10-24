// Playwright stand-in for the WebView integration:
//   addInitScript     ≈ WebViewCompat.addDocumentStartJavaScript (all frames, before page JS)
//   exposeBinding     ≈ WebViewCompat.addWebMessageListener (SereneBridge.postMessage / onmessage)
//   context.route     ≈ WebViewClient.shouldInterceptRequest
import fs from 'node:fs';
import { chromium, devices } from 'playwright';

const CONTENT_JS = fs.readFileSync(new URL('../src/content/serene.js', import.meta.url), 'utf8');
const SHIM = `
  window.SereneBridge = { postMessage: (s) => window.__sereneHost(s), onmessage: null };
  window.__sereneDeliver = (s) => { const b = window.SereneBridge; if (b && b.onmessage) b.onmessage({ data: s }); };
`;

const TYPE_MAP = { document: 'sub_frame', xhr: 'xmlhttprequest', fetch: 'xmlhttprequest', eventsource: 'other', websocket: 'websocket', manifest: 'other', texttrack: 'media' };

export async function launch({ headless = true } = {}) {
  const opts = { headless };
  if (process.env.SERENE_CHROMIUM) opts.executablePath = process.env.SERENE_CHROMIUM;
  return chromium.launch(opts);
}

/** New context emulating a phone. engine = SereneEngine or null (plain browser). */
export async function newContext(browser, { engine = null, onEvent = () => {}, locale = 'en-GB' } = {}) {
  const context = await browser.newContext({ ...devices['Pixel 7'], locale, ignoreHTTPSErrors: false });
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
    send: (obj) => frame.evaluate((s) => window.__sereneDeliver && window.__sereneDeliver(s), JSON.stringify(obj)).catch(() => {}),
  });

  await context.exposeBinding('__sereneHost', async (source, json) => {
    const msg = JSON.parse(json);
    const isTop = source.frame === source.page.mainFrame();
    // A new document in the main frame is a new navigation: fresh page session.
    if (msg.type === 'hello' && isTop) sessions.set(source.page, engine.newPage(onEvent));
    await sessionFor(source.page).onMessage(msg, frameCtx(source.frame));
  });
  await context.addInitScript({ content: SHIM + CONTENT_JS });

  if (engine.rules) {
    await context.route('**/*', (route) => {
      const req = route.request();
      let page = null;
      try { page = req.frame().page(); } catch {}
      const isMainDoc = req.isNavigationRequest() && page && req.frame() === page.mainFrame();
      if (!page || isMainDoc || !/^https?:/.test(req.url())) return route.continue();
      const type = TYPE_MAP[req.resourceType()] || req.resourceType();
      const source = page.mainFrame().url();
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
    return { overlays: out, scrollLocked: locked, scanMs: s.state.scanMs };
  });
}
