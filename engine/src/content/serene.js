// Serene content script.
//
// Injected at document start into every frame (WebViewCompat.addDocumentStartJavaScript on
// Android, page.addInitScript in the harness). It talks to the native side through
// `SereneBridge`, the object WebViewCompat.addWebMessageListener exposes: postMessage(string)
// out, onmessage({data}) in. The script does the DOM work (cosmetic CSS, overlay detection,
// feature extraction, hide/click); the host owns the rules engine, the LLM and the policy.
(() => {
  'use strict';
  if (window.__serene) return;
  const bridge = window.SereneBridge;
  if (!bridge) return;

  const IS_TOP = window === window.top;
  const ATTR = 'data-serene';
  const config = {
    observeOnly: false,   // harness baseline: detect and report, never act
    quarantineScore: 0.6, // hide a candidate while the host decides
    userGestureMs: 1500,  // overlays appearing this soon after a tap are user-initiated
    genericWaitMs: 1500,  // longest the first scan waits for the host's generic rules
    maxText: 320,
    maxButtons: 8,
    consentParkMs: 10000, // longest a list-matched cookie banner waits for autoconsent
    consentHoldMs: 5000,  // longest the detector waits while autoconsent answers a popup
    mutationScanMs: 100,  // a scan runs at most this long after the first DOM change (throttle, not debounce)
    newTokensWaitMs: 300, // longest a scan after DOM changes waits for generic rules for new class/id tokens
  };
  const state = {
    started: false,
    nextId: 1,
    candidates: new Map(), // id -> { el, buttons, sig }
    seen: new WeakSet(),
    siteRules: [],         // cached verdicts for this site from the host
    lastInput: -Infinity,
    sentClasses: new Set(),
    sentIds: new Set(),
    procedural: [],
    styleEl: null,
    consentEl: null,
    consentSelectors: new Set(),
    consentParked: false,  // list-matched cookie banners: parked for autoconsent, or hidden
    consent: { lifecycle: null, busySince: 0 }, // this frame's autoconsent progress
    scrollFixed: false,
    trapsReleasedAt: -Infinity,
    pendingRetries: 0,    // rescans for overlays that were on top but not visible yet
    scanMs: [],
  };
  const api = (window.__serene = { state, config, scan: () => scan(), extract: (el) => extract(el) });

  const send = (type, payload = {}) => bridge.postMessage(JSON.stringify({ type, ...payload }));
  bridge.onmessage = (ev) => {
    let msg;
    try { msg = JSON.parse(ev.data); } catch { return; }
    handlers[msg.type]?.(msg);
  };

  for (const t of ['pointerdown', 'keydown', 'touchstart']) {
    addEventListener(t, (e) => { if (e.isTrusted) state.lastInput = performance.now(); }, true);
  }
  // A page we unlocked can lock itself again with no DOM change for the observer to see (a
  // CSS-in-JS rule added through the CSSOM) and no scan coming. The user trying to scroll it is
  // the cue to look again (techspot, #24).
  for (const t of ['touchstart', 'wheel']) {
    addEventListener(t, (e) => { if (e.isTrusted && state.scrollFixed && !config.observeOnly && document.body && scrollLocked()) restorePage(); }, { capture: true, passive: true });
  }
  // A focus trap that started after we hid its dialog cancels touches from a document listener.
  // Seen here, after the page's listeners: let it go, unless a modal is still up (#24).
  for (const t of ['touchstart', 'mousedown']) {
    addEventListener(t, (e) => {
      if (!e.isTrusted || !e.defaultPrevented || config.observeOnly || performance.now() - state.trapsReleasedAt < 1000) return;
      if (hiddenEls().length && !otherModal()) releaseTraps();
    }, { passive: true });
  }

  // ---------------------------------------------------------------- host messages

  const handlers = {
    // Reply to hello: rules for this URL plus cached verdicts for the site.
    init(msg) {
      Object.assign(config, msg.config || {});
      state.siteRules = msg.siteRules || [];
      try {
        if (config.observeOnly) window.__sereneAutoconsent?.release?.();
        else {
          startAutoconsent(msg.autoconsent);
          addCss(msg.css || '');
          addConsentSelectors(msg.consentSelectors);
          if (msg.script) runScript(msg.script);
          state.procedural = msg.procedural || [];
        }
      } finally {
        start(); // whatever happens to the rules, the detector must still run
      }
    },
    generic(msg) {
      if (!config.observeOnly) { addCss(msg.css || ''); addConsentSelectors(msg.consentSelectors); }
      if (state.heldScan) { clearTimeout(state.heldScan); state.heldScan = 0; scan(); }
    },
    verdict(msg) { applyVerdict(msg); },
    // A parent frame wants us to click a button inside this (consent) iframe.
    frameClick(msg) {
      const s = frameSummary();
      const b = s && s.buttonEls[msg.target];
      if (b) { b.click(); send('applied', { id: msg.id, action: 'click', ok: true, inFrame: true }); }
    },
    rescan() { scan(); },
  };

  function addCss(css) {
    if (!css) return;
    if (!state.styleEl) {
      state.styleEl = document.createElement('style');
      state.styleEl.setAttribute(ATTR, 'rules');
    }
    state.styleEl.textContent += css + '\n';
    if (!state.styleEl.isConnected) attachStyle(state.styleEl);
  }

  // The host's reply can arrive before the parser has created <html> (slow first bytes), so
  // wait for a parent instead of throwing; a throw here used to stop the detector starting.
  function attachStyle(el) {
    const parent = document.head || document.documentElement;
    if (parent) { parent.appendChild(el); return; }
    const mo = new MutationObserver(() => {
      const p = document.head || document.documentElement;
      if (!p) return;
      mo.disconnect();
      p.appendChild(el);
    });
    mo.observe(document, { childList: true, subtree: true });
  }

  // ---------------------------------------------------------------- cookie banners + autoconsent

  // Cookie banners the lists know are parked, not hidden, while autoconsent answers them:
  // invisible and click-through, but still laid out, because autoconsent only acts on a popup
  // it can see (display:none would hide it from its checks). Once autoconsent is done with
  // the frame, or after consentParkMs, they are hidden like any other list match.
  function addConsentSelectors(sels) {
    if (!sels || !sels.length) return;
    for (const s of sels) state.consentSelectors.add(s);
    renderConsentCss();
  }

  function renderConsentCss() {
    if (!state.consentSelectors.size) return;
    if (!state.consentEl) {
      state.consentEl = document.createElement('style');
      state.consentEl.setAttribute(ATTR, 'consent');
    }
    const decl = state.consentParked ? 'opacity:0!important;pointer-events:none!important' : 'display:none!important';
    state.consentEl.textContent = `${[...state.consentSelectors].join(',\n')}{${decl}}`;
    if (!state.consentEl.isConnected) attachStyle(state.consentEl);
  }

  function releaseConsent() {
    if (!state.consentParked) return;
    state.consentParked = false;
    clearTimeout(state.parkTimer);
    renderConsentCss();
  }

  const AC_BUSY = new Set(['openPopupDetected', 'runningOptOut']);
  const AC_DONE = new Set(['nothingDetected', 'optOutSucceeded', 'optOutFailed', 'done']);
  const AC_EVENTS = new Set(['popupFound', 'optOutResult', 'autoconsentDone', 'autoconsentError']);

  function startAutoconsent(ac) {
    const lib = window.__sereneAutoconsent;
    if (!lib) return;
    if (!ac) { lib.release?.(); return; } // no autoconsent here: drop its document-start prehide
    state.consentParked = true;
    state.parkTimer = setTimeout(releaseConsent, config.consentParkMs);
    try { lib.start(ac, onAutoconsent); } catch (e) { releaseConsent(); send('log', { msg: 'autoconsent error: ' + e.message }); }
  }

  function onAutoconsent(m) {
    if (m.type === 'report') {
      const lc = m.state && m.state.lifecycle;
      if (lc === state.consent.lifecycle) return;
      const wasBusy = consentBusy();
      state.consent.lifecycle = lc;
      if (AC_BUSY.has(lc) && !wasBusy) {
        state.consent.busySince = performance.now();
        setTimeout(() => scan(), config.consentHoldMs + 50); // in case it never finishes
      }
      if (AC_DONE.has(lc)) releaseConsent();
      if (wasBusy && !AC_BUSY.has(lc)) setTimeout(() => scan(), 300); // what is left after it
      return;
    }
    if (AC_EVENTS.has(m.type)) {
      send('autoconsent', { event: m.type, origin: location.origin, cmp: m.cmp, result: m.result, isCosmetic: m.isCosmetic, clicks: m.totalClicks, duration: m.duration, details: m.details && String(m.details.msg || '') });
    }
  }

  // While autoconsent is answering a popup in this frame the detector stays out of its way.
  function consentBusy() {
    return AC_BUSY.has(state.consent.lifecycle) && performance.now() - state.consent.busySince < config.consentHoldMs;
  }

  function runScript(code) {
    // Document-start scripts already run in the page's main world; scriptlets need that too.
    try { (0, eval)(code); } catch (e) { send('log', { msg: 'scriptlet error: ' + e.message }); }
  }

  function start() {
    if (state.started) return;
    state.started = true;
    const go = () => {
      // First scan waits for the generic class/id rules so that list-covered banners are
      // already hidden and never reach the detector. A busy host (phone main thread during
      // page load) can take a second to answer; no scan runs before the answer or the timeout.
      if (sendClassIds()) holdScan(config.genericWaitMs); else scan();
      const mo = new MutationObserver(onMutations);
      mo.observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'style', 'id', 'open', 'hidden'] });
      // Popups that fade or slide in change no DOM when they finish; neither do stylesheets
      // that make a server-rendered banner fixed once they load.
      for (const t of ['transitionend', 'animationend']) addEventListener(t, () => schedule(), true);
      addEventListener('load', () => schedule(), { once: true });
      for (const ms of [300, 800, 2000, 4000, 7000, 11000]) setTimeout(scan, ms);
    };
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', go, { once: true });
    else go();
  }

  // Scans after DOM changes are throttled, not debounced: a page that keeps mutating while it
  // loads used to push the scan back for as long as it kept changing (the 2026-10-24 crawl saw
  // overlays on screen for seconds). New class/id tokens go to the host first, and the scan
  // waits briefly for its generic rules so that list-covered banners never reach the detector.
  let scanTimer = 0;
  function onMutations(records) {
    let relevant = false;
    for (const r of records) {
      if (r.target && r.target.nodeType === 1 && r.target.hasAttribute(ATTR)) continue;
      relevant = true;
      break;
    }
    if (relevant) schedule();
  }

  // The gap grows with what a tick costs (at most ~10% of the main thread on a slow phone).
  let tickMs = 0;
  function schedule(ms = config.mutationScanMs) {
    if (scanTimer) return;
    scanTimer = setTimeout(() => {
      scanTimer = 0;
      const t0 = performance.now();
      if (sendClassIds()) holdScan(config.newTokensWaitMs); else scan();
      tickMs = performance.now() - t0;
    }, Math.max(ms, Math.round(tickMs * 10)));
  }

  // Hold scans until the host's generic reply (handlers.generic) or the timeout.
  function holdScan(ms) {
    if (state.heldScan) return;
    state.heldScan = setTimeout(() => { state.heldScan = 0; scan(); }, ms);
  }

  // ---------------------------------------------------------------- rules: generic + procedural

  function sendClassIds() {
    if (config.observeOnly) return;
    const classes = [], ids = [];
    for (const el of document.querySelectorAll('[class],[id]')) {
      if (el.id && !state.sentIds.has(el.id)) { state.sentIds.add(el.id); ids.push(el.id); }
      for (const c of el.classList) if (!state.sentClasses.has(c)) { state.sentClasses.add(c); classes.push(c); }
    }
    applyProcedural();
    if (classes.length || ids.length) { send('classIds', { classes, ids }); return true; }
    return false;
  }

  // Subset of uBO procedural operators that adblock-rust hands back as JSON.
  function applyProcedural() {
    for (const f of state.procedural) {
      let els = null;
      try {
        for (const op of f.selector) {
          if (op.type === 'css-selector') {
            els = els ? els.flatMap((e) => [...e.querySelectorAll(op.arg)]) : [...document.querySelectorAll(op.arg)];
          } else if (!els) {
            els = [];
          } else if (op.type === 'has-text') {
            const re = toRegex(op.arg);
            els = els.filter((e) => re.test(e.textContent || ''));
          } else if (op.type === 'min-text-length') {
            els = els.filter((e) => (e.textContent || '').length >= Number(op.arg));
          } else if (op.type === 'upward') {
            const n = Number(op.arg);
            els = els.map((e) => {
              if (!Number.isNaN(n)) { let p = e; for (let i = 0; i < n && p; i++) p = p.parentElement; return p; }
              return e.parentElement && e.parentElement.closest(op.arg);
            }).filter(Boolean);
          } else if (op.type === 'matches-css') {
            const [prop, val] = op.arg.split(/:\s*/);
            const re = toRegex(val || '');
            els = els.filter((e) => re.test(getComputedStyle(e).getPropertyValue(prop.trim())));
          } else {
            els = []; // unsupported operator: skip the filter rather than guess
          }
        }
      } catch { els = []; }
      for (const el of els || []) {
        const a = f.action;
        if (!a) hideEl(el, 'procedural');
        else if (a.type === 'remove') el.remove();
        else if (a.type === 'style') el.style.cssText += ';' + a.arg;
        else if (a.type === 'remove-attr') el.removeAttribute(a.arg);
        else if (a.type === 'remove-class') el.classList.remove(a.arg);
      }
    }
  }

  function toRegex(arg) {
    const m = /^\/(.*)\/([imsu]*)$/.exec(arg);
    if (m) return new RegExp(m[1], m[2]);
    return new RegExp(arg.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'));
  }

  // ---------------------------------------------------------------- overlay detection

  // Sample points across the viewport; anything that covers the user's view shows up in
  // elementsFromPoint at one of them. Much cheaper than computing styles for every node.
  function samplePoints() {
    const w = innerWidth, h = innerHeight, pts = [];
    for (const fx of [0.15, 0.5, 0.85]) for (const fy of [0.03, 0.2, 0.4, 0.6, 0.8, 0.97]) pts.push([w * fx, h * fy]);
    return pts;
  }

  function isOverlayPos(el) {
    const p = getComputedStyle(el).position;
    return p === 'fixed' || p === 'sticky';
  }

  // Outermost fixed/sticky ancestor (crossing open shadow roots), or null.
  function overlayRoot(el) {
    let root = null;
    for (let n = el; n && n !== document.documentElement && n !== document.body; ) {
      if (n.nodeType === 1 && isOverlayPos(n)) root = n;
      n = n.parentElement || (n.getRootNode && n.getRootNode().host) || null;
    }
    return root;
  }

  function visible(el) {
    if (!el.isConnected) return false;
    const cs = getComputedStyle(el);
    if (cs.display === 'none' || cs.visibility === 'hidden' || Number(cs.opacity) < 0.05) return false;
    const r = el.getBoundingClientRect();
    return r.width > 1 && r.height > 1;
  }

  function scan() {
    if (state.heldScan) return [];
    const t0 = performance.now();
    try { return scanInner(); } finally { state.scanMs.push(Math.round((performance.now() - t0) * 10) / 10); }
  }

  function scanInner() {
    if (!document.body) return [];
    if (!IS_TOP) { reportFrame(); return []; }
    if (consentBusy()) return [];
    const roots = new Set();
    for (const [x, y] of samplePoints()) {
      let stack;
      try { stack = document.elementsFromPoint(x, y); } catch { continue; }
      for (const el of stack.slice(0, 6)) {
        if (el === document.documentElement || el === document.body) break;
        const root = overlayRoot(el);
        if (root) { roots.add(root); break; }
      }
    }
    // <dialog open> modal and aria-modal elements that are not positioned fixed.
    for (const el of document.querySelectorAll('dialog[open],[aria-modal="true"]')) if (visible(el)) roots.add(el);

    const out = [];
    let pending = false;
    for (const root of roots) {
      if (state.seen.has(root)) continue;
      // On top but not visible yet: fading in (opacity) or not laid out. Look again soon.
      if (!visible(root)) { if (root.isConnected && !root.hasAttribute(ATTR)) pending = true; continue; }
      const f = extract(root);
      if (!f) continue;
      state.seen.add(root);
      const id = state.nextId++;
      state.candidates.set(id, { el: root, buttons: f._buttons, sig: f.sig, iframes: f._iframes, backdrop: f.isBackdrop, modalish: !f.isBackdrop && !f.looksLikeLayout && f.edge !== 'top' && f.coverage > 0.05, seenAt: performance.now() });
      delete f._buttons; delete f._iframes;
      out.push({ id, ...f });
      handleCandidate(id, f);
    }
    if (pending && state.pendingRetries < 10) { state.pendingRetries++; schedule(200); }
    // A locked page with nothing visible on top is what filter-list hides and cached hides
    // leave behind (the banner is gone, its scroll lock is not).
    const backdrops = [...state.candidates.values()].filter((c) => c.backdrop && c.el.isConnected && !c.el.hasAttribute(ATTR) && visible(c.el));
    // Only a candidate still waiting for its verdict holds this back; a quarantine that outlived
    // its verdict (hidden, or clicked in a frame) used to block it for good (techspot, #24).
    const blocking = [...roots].some((r) => !r.hasAttribute(ATTR) && visible(r) && !backdrops.some((b) => b.el === r) && coverage(r.getBoundingClientRect()) > 0.3)
      || [...state.candidates.values()].some((c) => c.quarantined && !c.decided);
    const idle = performance.now() - state.lastInput > config.userGestureMs;
    // An empty full-screen layer with no modal on it (the lists or a hide took the modal) only
    // dims the page and swallows taps. Give a modal 2 s to show up on it first, unless the
    // layer wraps a modal we already hid (a consent tool's dimmed wrapper that faded in late).
    const modalUp = [...state.candidates.values()].some((c) => c.modalish && c.el.isConnected && !c.el.hasAttribute(ATTR) && visible(c.el));
    const hidden = hiddenEls();
    const wrapsHidden = (b) => hidden.some((el) => b.el.contains(el));
    const stray = modalUp ? [] : backdrops.filter((b) => scrollLocked() || performance.now() - b.seenAt > 2000 || wrapsHidden(b));
    // Other strays wait for the user to be idle (a tap may have opened them). A wrapper of a modal
    // we hid does not: the user is touching it to scroll past it, and each touch put it off (#24).
    const clear = idle ? stray : stray.filter(wrapsHidden);
    if (!config.observeOnly && !blocking && clear.length) {
      for (const b of clear) hideEl(b.el, 'backdrop');
      // Clearing the wrapper of a modal we hid is the hide's own cleanup, which a modal hidden by
      // a cached verdict never got: let go of its focus trap and keep its page unlocked.
      if (clear.some(wrapsHidden)) { releaseTraps(); restorePage(); } else fixScroll();
    }
    else if (!config.observeOnly && !blocking && idle && scrollLocked()) fixScroll();
    else if (state.scrollFixed) restorePage();
    return out;
  }

  function handleCandidate(id, f) {
    if (config.observeOnly) { send('candidate', { id, features: f }); return; }
    if (f.userInitiated) { send('candidate', { id, features: f }); return; } // host logs it, policy ignores
    // Cached verdict for this site: act locally without a round trip.
    const rule = state.siteRules.find((r) => r.sig === f.sig);
    if (rule) {
      applyVerdict({ id, ...rule, source: 'cache' });
      send('candidate', { id, features: f, cached: true });
      return;
    }
    if (f.heuristic.score >= config.quarantineScore && !f.isBackdrop) quarantine(id, true);
    send('candidate', { id, features: f });
  }

  // ---------------------------------------------------------------- features

  const BUTTON_SEL = 'button,[role="button"],a[href],input[type="submit"],input[type="button"],[onclick],[aria-label*="close" i],[class*="close" i]';

  function deepText(el) {
    // innerText respects visibility; open shadow roots are walked separately.
    let t = el.innerText || '';
    if (t.trim().length < 10) {
      const walk = (n) => {
        if (n.shadowRoot) t += ' ' + (n.shadowRoot.textContent || '');
        for (const c of n.children || []) walk(c);
      };
      walk(el);
    }
    return t.replace(/\s+/g, ' ').trim();
  }

  function deepQueryAll(el, sel) {
    const out = [...el.querySelectorAll(sel)];
    const walk = (n) => {
      if (n.shadowRoot) { out.push(...n.shadowRoot.querySelectorAll(sel)); for (const c of n.shadowRoot.children) walk(c); }
      for (const c of n.children || []) walk(c);
    };
    walk(el);
    return out;
  }

  function buttonLabel(b) {
    const t = (b.innerText || b.value || b.getAttribute('aria-label') || b.getAttribute('title') || '').replace(/\s+/g, ' ').trim();
    if (t) return t.slice(0, 40);
    const cls = (b.getAttribute('class') || '') + ' ' + (b.id || '');
    if (/close|dismiss/i.test(cls)) return '×';
    return '';
  }

  function collectButtons(root) {
    const seen = new Set(), out = [];
    for (const b of deepQueryAll(root, BUTTON_SEL)) {
      if (seen.has(b) || !visible(b)) continue;
      // Skip wrappers that contain another candidate button.
      if (b.querySelector && b.querySelector('button,a[href]') && !/^(BUTTON|A)$/.test(b.tagName)) continue;
      seen.add(b);
      const label = buttonLabel(b);
      if (!label) continue;
      out.push({ el: b, label, link: b.tagName === 'A' });
    }
    // Buttons first, then links (privacy-policy links matter less), keep DOM order inside each.
    out.sort((a, b) => a.link - b.link);
    return out.slice(0, config.maxButtons);
  }

  function coverage(r) {
    const w = Math.max(0, Math.min(r.right, innerWidth) - Math.max(r.left, 0));
    const h = Math.max(0, Math.min(r.bottom, innerHeight) - Math.max(r.top, 0));
    return (w * h) / (innerWidth * innerHeight);
  }

  function scrollLocked() {
    const h = getComputedStyle(document.documentElement), b = getComputedStyle(document.body);
    return h.overflow === 'hidden' || h.overflowY === 'hidden' || b.overflow === 'hidden' || b.overflowY === 'hidden' || b.position === 'fixed';
  }

  const STABLE_TOKEN = (s) => s && s.length < 40 && !/\d{3,}|^[a-z]{1,3}-(?=[a-z]*\d)[a-z0-9]{5,}$|^_|__[a-z0-9]{5}$|[A-Z].*[0-9]|[0-9].*[A-Z]/.test(s) && !/^(css|sc|jsx|emotion|svelte)-/.test(s);

  // Stable selector for caching a verdict as a site rule (eTLD+1 + sig).
  function signature(el) {
    const part = (n) => {
      let s = n.localName;
      if (n.id && STABLE_TOKEN(n.id)) return s + '#' + CSS.escape(n.id);
      const cls = [...n.classList].filter(STABLE_TOKEN).sort().slice(0, 3);
      if (cls.length) return s + cls.map((c) => '.' + CSS.escape(c)).join('');
      const role = n.getAttribute('role');
      if (role) return s + `[role="${role}"]`;
      return null;
    };
    const own = part(el);
    if (own && (own.includes('#') || own.includes('.'))) return own;
    const p = el.parentElement && el.parentElement !== document.body ? part(el.parentElement) : 'body';
    return (p || el.parentElement?.localName || 'body') + ' > ' + (own || el.localName);
  }

  function edge(r) {
    const cov = coverage(r);
    if (cov > 0.8) return 'full';
    if (r.top <= 4 && r.height < innerHeight * 0.5) return 'top';
    if (r.bottom >= innerHeight - 4 && r.height < innerHeight * 0.6) return 'bottom';
    return 'center';
  }

  function extract(root) {
    const r = root.getBoundingClientRect();
    const cov = coverage(r);
    const text = deepText(root);
    const buttons = collectButtons(root);
    const iframes = deepQueryAll(root, 'iframe').filter(visible);
    const iframeOrigins = [...new Set(iframes.map((f) => { try { return new URL(f.src, location.href).origin; } catch { return ''; } }).filter(Boolean))];
    const small = cov < 0.03 && r.height < 48;
    if (small && !buttons.length) return null;
    // Large text means this is probably an app shell or a sticky layout, not an overlay.
    const big = text.length > 4000 || root.querySelector('article,main');
    const f = {
      sig: signature(root),
      tag: root.localName,
      role: root.getAttribute('role') || '',
      ariaModal: root.getAttribute('aria-modal') === 'true' || root.localName === 'dialog',
      ariaLabel: (root.getAttribute('aria-label') || '').slice(0, 60),
      tokens: [root.id, ...root.classList].filter(Boolean).slice(0, 8).join(' ').slice(0, 120),
      text: text.slice(0, config.maxText),
      textLen: text.length,
      buttons: buttons.map((b) => b.label),
      hasEmail: deepQueryAll(root, 'input[type="email"],input[name*="mail" i],input[placeholder*="mail" i]').length > 0,
      hasPassword: deepQueryAll(root, 'input[type="password"]').length > 0,
      hasVideo: deepQueryAll(root, 'video').length > 0,
      iframeOrigins,
      coverage: Math.round(cov * 100) / 100,
      edge: edge(r),
      zIndex: Number(getComputedStyle(root).zIndex) || 0,
      scrollLocked: scrollLocked(),
      userInitiated: performance.now() - state.lastInput < config.userGestureMs,
      isBackdrop: text.length < 3 && !buttons.length && cov > 0.6 && !iframes.length,
      looksLikeLayout: !!big,
      url: location.href,
    };
    f.heuristic = heuristics(f);
    f._buttons = buttons.map((b) => b.el);
    f._iframes = iframes;
    return f;
  }

  // ---------------------------------------------------------------- heuristics

  // Multilingual keyword sets. Cookie banners carry most of the language variety.
  const KW = {
    cookie_consent: /cookie|consent|gdpr|privacy (settings|choices|preferences)|we (and our partners )?(use|value)|personali[sz]ed (ads|content)|legitimate interest|tcf|datenschutz|einwilligung|zustimm|akzeptieren|alle ablehnen|cookies?\b|consentement|accepter|refuser|aceptar|rechazar|accetta|rifiuta|aceitar|rejeitar|toestemming|akkoord|weiger|zgod|ciasteczk|souhlas|odmítnout|elfogad|hyväksy|godkänn|neka|samtyk|afvis/i,
    newsletter: /newsletter|subscribe|sign ?up|join (our|the)|get (the latest|our|exclusive)|inbox|e-?mail address|stay (in the loop|updated|informed)|abonn|anmelden|inscri|suscr|iscriviti|% off|discount|coupon|promo code/i,
    notification_prompt: /notifications?|push|alerts?|stay notified|get notified|turn on|benachrichtigung|notificaci|notifiche/i,
    paywall_teaser: /subscribe to (continue|read)|(free|remaining) articles?|article limit|you('ve| have) (read|reached)|unlimited (access|digital)|already a (subscriber|member)|support (our|independent) journalism|become a (member|subscriber)|abo(nnement)?\b|plus\b.*lesen/i,
    app_install: /open in (the )?app|get the app|download (the|our) app|use the app|install|app store|google play|in der app|nell'app|en la app/i,
    login_wall: /sign in to (continue|read)|log ?in to (continue|see)|create (a free )?account to|register to continue|continue with (google|apple|facebook)/i,
    survey: /survey|feedback|how (likely|satisfied)|rate (us|your)|quick question/i,
    chat_widget: /chat with us|need help\?|how can we help|live chat|ask us/i,
  };
  const CMP_HOSTS = /cookielaw|onetrust|cookiebot|usercentrics|didomi|sourcepoint|privacy-mgmt|consensu|trustarc|quantcast|cmp|consentmanager|iubenda|termly|cookieyes|osano|sp-prod/i;
  const REJECT = /reject|decline|refuse|deny|disagree|necessary only|only necessary|essential only|only essential|continue without|ablehnen|nur (notwendige|erforderliche)|ohne zustimmung|refuser|continuer sans|rechazar|rifiuta|rejeitar|weiger|odrzu|odmítnout|nein,? danke|no,? thanks|not now|maybe later|later|no thanks|close|dismiss|^×$|^✕$|^✖$|^x$|schließen|später|nicht jetzt|fermer|plus tard|non merci|cerrar|ahora no|chiudi|non ora|fechar|agora não|sluiten|weigeren|zamknij|nie teraz|zavřít|avvisa|stäng|afvis|avvis|\bluk\b|hylkää|sulje|reddet|kapat|отклонить|отказаться|закрыть|не сейчас|拒否|同意しない|閉じる|後で|今はしない|拒绝|拒絕|关闭|關閉|不同意|稍后|거부|거절|동의하지 않|닫기|나중에/i;
  const ACCEPT = /accept|agree|allow( all)?$|got it|^ok(ay)?$|i understand|consent|subscribe|abbona|abonn|suscri|subskry|assinar|akzeptieren|zustimmen|einverstanden|accepter|j'accepte|aceptar|accetta|aceitar|akkoord|zgadzam|souhlasím|elfogad|godkänn|acceptera|hyväksy|kabul|принять|согласен|разрешить|(?<!不)同意(?!しない)|許可|承認|受け入れ|接受|允许|允許|동의(?!하지)|수락|허용/i;

  function heuristics(f) {
    const hay = `${f.text} ${f.buttons.join(' ')} ${f.tokens} ${f.ariaLabel}`;
    const scores = {};
    for (const [k, re] of Object.entries(KW)) {
      const m = hay.match(new RegExp(re.source, 'gi'));
      scores[k] = m ? Math.min(1, 0.35 + 0.15 * m.length) : 0;
    }
    if (f.iframeOrigins.some((o) => CMP_HOSTS.test(o)) || CMP_HOSTS.test(f.tokens)) scores.cookie_consent = Math.max(scores.cookie_consent, 0.9);
    if (f.buttons.some((b) => ACCEPT.test(b)) && scores.cookie_consent > 0) scores.cookie_consent += 0.15;
    if (f.hasEmail) scores.newsletter += 0.25;
    let label = 'content', best = 0;
    for (const [k, v] of Object.entries(scores)) if (v > best) { best = v; label = k; }
    // Structure: blocking overlays are modal-ish, cover the page or lock scrolling.
    let structure = 0;
    if (f.coverage > 0.5) structure += 0.15;
    if (f.scrollLocked) structure += 0.15;
    if (f.ariaModal) structure += 0.1;
    if (f.zIndex > 1000) structure += 0.05;
    if (f.edge === 'bottom' || f.edge === 'center' || f.edge === 'full') structure += 0.05;
    if (f.buttons.length && f.buttons.length <= 4 && f.edge !== 'top') structure += 0.1; // a few choices: a prompt
    // Against: page chrome and media.
    let against = 0;
    if (f.looksLikeLayout) against += 0.5;
    if (f.hasVideo) against += 0.3;
    if (f.edge === 'top' && f.coverage < 0.15 && (/^(header|nav)$/.test(f.tag) || /banner|navigation|menubar/.test(f.role) || /header|nav|masthead|top-?bar|toolbar|menu/i.test(f.tokens))) against += 0.4;
    if (f.hasPassword && label !== 'login_wall') against += 0.2;
    if (f.buttons.length > 6 && best < 0.6) against += 0.2; // menus and toolbars
    const score = best > 0 ? Math.max(0, Math.min(1, best + structure - against)) : Math.max(0, structure - against - 0.2);
    const rejectIdx = f.buttons.findIndex((b) => REJECT.test(b) && !ACCEPT.test(b));
    return { label, score: Math.round(score * 100) / 100, structure: Math.round((structure - against) * 100) / 100, rejectIdx, scores };
  }
  api.heuristics = heuristics;

  // ---------------------------------------------------------------- iframes (CMP dialogs)

  function frameSummary() {
    if (!document.body) return null;
    const text = deepText(document.body);
    const buttons = collectButtons(document.body);
    if (!buttons.length || text.length > 3000) return null;
    return { text: text.slice(0, config.maxText), buttons: buttons.map((b) => b.label), buttonEls: buttons.map((b) => b.el) };
  }

  let frameReported = false;
  function reportFrame() {
    if (frameReported) return;
    const s = frameSummary();
    if (!s) return;
    frameReported = true;
    // Keyword heuristics on the frame's own text, so the host can decide without the model.
    const heuristic = heuristics({ text: s.text, buttons: s.buttons, tokens: '', ariaLabel: '', tag: 'body', role: '', iframeOrigins: [location.origin], hasEmail: false, hasPassword: false, hasVideo: false, coverage: 1, edge: 'full', zIndex: 0, scrollLocked: false, ariaModal: false, looksLikeLayout: false });
    send('frameSummary', { origin: location.origin, text: s.text, buttons: s.buttons, heuristic });
  }

  // ---------------------------------------------------------------- actions

  function quarantine(id, on) {
    const c = state.candidates.get(id);
    if (!c) return;
    if (on) { c.el.style.setProperty('visibility', 'hidden', 'important'); c.quarantined = true; }
    else if (c.quarantined) { c.el.style.removeProperty('visibility'); c.quarantined = false; }
  }

  function hideEl(el, why) {
    el.style.setProperty('display', 'none', 'important');
    el.setAttribute(ATTR, why);
  }

  function applyVerdict(v) {
    const c = state.candidates.get(v.id);
    if (!c) return;
    if (config.observeOnly) return;
    c.decided = true;
    if (v.action === 'ignore') { quarantine(v.id, false); return; }
    if (v.action === 'click') {
      let btn = typeof v.target === 'number' ? c.buttons[v.target] : null;
      // Cached rules refer to the button by label because indexes shift between visits.
      if (v.buttonLabel) btn = c.buttons.find((b) => buttonLabel(b) === v.buttonLabel) || btn;
      if (v.inFrame) {
        send('frameClick', { id: v.id, origin: v.inFrame, target: v.target });
      } else if (btn) {
        quarantine(v.id, false);
        c.el.style.setProperty('opacity', '0', 'important'); // keep it clickable but out of sight
        try { btn.click(); } catch {}
      }
      // If the banner is still there shortly after, fall back to hiding it.
      setTimeout(() => {
        const still = c.el.isConnected && visible(c.el);
        if (still) hide(c, v.id);
        else restorePage();
        send('applied', { id: v.id, action: 'click', ok: !still, fallback: still ? 'hide' : null, source: v.source });
      }, 900);
      return;
    }
    hide(c, v.id);
    send('applied', { id: v.id, action: 'hide', ok: true, source: v.source });
  }

  function hide(c) {
    c.el.style.removeProperty('visibility');
    c.hidden = true;
    hideEl(c.el, 'hidden');
    hideBackdrops(c.el);
    // Backdrops that are not siblings of the modal (pinterest nests them apart) were seen as
    // their own candidates; with the modal gone they only lock the page.
    for (const b of state.candidates.values()) if (b.backdrop && b.el.isConnected && !b.el.hasAttribute(ATTR)) hideEl(b.el, 'backdrop');
    if (c.modalish) releaseTraps();
    restorePage();
  }

  // Focus traps (the focus-trap library and the like) cancel every touch and click outside their
  // dialog from listeners on the document, which outlive the dialog we hid: the page shows but
  // won't scroll or follow links (techspot's consent tool, #24). Escape is how they let go. Sent
  // to the body, not the dialog, so the dialog's own key handlers don't take it as an answer.
  function releaseTraps() {
    if (!document.body) return;
    state.trapsReleasedAt = performance.now();
    document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', bubbles: true, cancelable: true, composed: true }));
  }

  // Full-viewport fixed elements without content next to the modal are its backdrop.
  function hideBackdrops(modal) {
    const z = Number(getComputedStyle(modal).zIndex) || 0;
    const parent = modal.parentElement || document.body;
    const scope = [...parent.children, ...document.body.children];
    for (const el of scope) {
      if (el === modal || el.hasAttribute(ATTR) || !visible(el)) continue;
      const cs = getComputedStyle(el);
      if (cs.position !== 'fixed') continue;
      const r = el.getBoundingClientRect();
      if (coverage(r) < 0.6 || deepText(el).length > 2) continue;
      if ((Number(cs.zIndex) || 0) < z - 1000) continue;
      hideEl(el, 'backdrop');
    }
  }

  // Undo scroll locks and blur that modals leave behind, unless another modal is still up.
  function restorePage() {
    if (otherModal()) return;
    state.scrollFixed = true;
    fixScroll();
  }

  // What Serene hid: candidates it hid, and elements a cached hide verdict hides with CSS before
  // the detector ever sees them (a reload, techspot #24).
  function hiddenEls() {
    const els = [...state.candidates.values()].filter((c) => c.hidden).map((c) => c.el);
    for (const r of state.siteRules) {
      if (r.action !== 'hide') continue;
      try { els.push(...document.querySelectorAll(r.sig)); } catch {}
    }
    return els;
  }

  function otherModal() {
    return [...state.candidates.values()].some((c) => !c.backdrop && c.el.isConnected && !c.el.hasAttribute(ATTR) && visible(c.el) && coverage(c.el.getBoundingClientRect()) > 0.5);
  }

  function fixScroll() {
    for (const el of [document.documentElement, document.body]) {
      const cs = getComputedStyle(el);
      if (cs.overflow === 'hidden' || cs.overflowY === 'hidden') el.style.setProperty('overflow', 'auto', 'important');
      if (el === document.body && cs.position === 'fixed') {
        const top = parseInt(el.style.top, 10) || 0;
        el.style.setProperty('position', 'static', 'important');
        if (top) scrollTo(0, -top);
      }
    }
    for (const el of document.body.children) {
      const f = getComputedStyle(el).filter;
      if (f && f.includes('blur')) el.style.setProperty('filter', 'none', 'important');
    }
  }

  send('hello', { url: location.href, top: IS_TOP });
})();
