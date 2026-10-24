// Host side of the hybrid engine: what the Android app's native layer will do.
// One PageSession per tab navigation; frames talk to it through the bridge.
import { preDecide, verdict, DEFAULT_POLICY } from './policy.mjs';
import { siteOf } from './cache.mjs';

export class SereneEngine {
  /**
   * @param rules       RulesEngine or null (LLM/heuristics only)
   * @param classifier  object with classify(features) → {label, target, ms}, or null
   * @param cache       VerdictCache
   */
  constructor({ rules = null, classifier = null, cache, policy = DEFAULT_POLICY, contentConfig = {} }) {
    Object.assign(this, { rules, classifier, cache, policy, contentConfig });
  }

  newPage(onEvent = () => {}) { return new PageSession(this, onEvent); }
}

class PageSession {
  constructor(engine, onEvent) {
    this.e = engine;
    this.emit = onEvent;
    this.frames = new Map();       // origin -> frame (for consent iframes)
    this.frameSummaries = new Map(); // origin -> {text, buttons}
    this.candidates = new Map();   // id -> {features, verdict}
    this.topUrl = null;
    this.exceptions = [];
  }

  /** Network hook (shouldInterceptRequest). */
  shouldBlock(url, type, sourceUrl = this.topUrl) {
    if (!this.e.rules || !sourceUrl) return false;
    const blocked = this.e.rules.shouldBlock(url, sourceUrl, type);
    if (blocked) this.emit({ type: 'blocked', url, resourceType: type });
    return blocked;
  }

  /** A message from a frame's content script. frame = {url, isTop, send(obj)} */
  async onMessage(msg, frame) {
    switch (msg.type) {
      case 'hello': return this.hello(msg, frame);
      case 'classIds': return this.classIds(msg, frame);
      case 'candidate': return this.candidate(msg, frame);
      case 'frameSummary':
        this.frames.set(msg.origin, frame);
        this.frameSummaries.set(msg.origin, { text: msg.text, buttons: msg.buttons, heuristic: msg.heuristic });
        return;
      case 'frameClick': {
        const f = this.frames.get(msg.origin);
        f?.send({ type: 'frameClick', id: msg.id, target: msg.target });
        return;
      }
      case 'applied': return this.applied(msg);
      case 'log': this.emit({ type: 'log', msg: msg.msg }); return;
    }
  }

  hello(msg, frame) {
    if (frame.isTop) this.topUrl = msg.url;
    const out = { type: 'init', config: this.e.contentConfig, css: '', siteRules: [] };
    const t0 = performance.now();
    if (this.e.rules) {
      const c = this.e.rules.cosmetic(msg.url);
      this.exceptions = c.exceptions;
      out.css = c.hideSelectors.length ? `${c.hideSelectors.join(',\n')}{display:none!important}` : '';
      out.script = c.script;
      out.procedural = c.procedural;
      if (frame.isTop) this.generichide = c.generichide;
      this.emit({ type: 'cosmetic', url: msg.url, hideSelectors: c.hideSelectors.length, procedural: c.procedural.length, scriptlets: !!c.script, ms: Math.round(performance.now() - t0) });
    }
    if (frame.isTop) {
      out.siteRules = this.e.cache.rulesFor(msg.url);
      const cached = this.e.cache.cssFor(msg.url);
      if (cached) out.css += '\n' + cached;
    }
    frame.send(out);
  }

  classIds(msg, frame) {
    // Always reply: the content script holds its first scan until this arrives.
    const sels = this.e.rules && !this.generichide ? this.e.rules.genericSelectors(msg.classes, msg.ids, this.exceptions) : [];
    if (sels.length) this.emit({ type: 'generic', selectors: sels });
    frame.send({ type: 'generic', css: sels.length ? `${sels.join(',\n')}{display:none!important}` : '' });
  }

  async candidate(msg, frame) {
    const f = msg.features;
    const rec = { id: msg.id, features: f, cached: !!msg.cached, t: performance.now() };
    this.candidates.set(msg.id, rec);
    if (msg.cached) { rec.verdict = { source: 'cache' }; this.emit({ type: 'candidate', ...rec }); return; }
    if (this.e.contentConfig.observeOnly) { this.emit({ type: 'candidate', ...rec }); return; }

    // A dialog inside a cross-origin iframe (typical for CMPs): the top frame sees an empty
    // box, so wait briefly for that frame's own report and classify its text and buttons.
    let feats = f, v = null;
    if (f.iframeOrigins?.length && f.textLen < 20 && !f.userInitiated) {
      const origin = await this.waitForFrame(f.iframeOrigins, 1500);
      if (origin) {
        const s = this.frameSummaries.get(origin);
        // The top frame saw an empty box; judge the frame's own text and buttons instead.
        feats = { ...f, text: s.text, buttons: s.buttons, heuristic: s.heuristic ?? f.heuristic };
        rec.inFrame = origin;
      }
    }

    v ??= preDecide(feats, { llmAvailable: !!this.e.classifier, policy: this.e.policy });
    if (v.needsLlm) {
      try {
        const r = await this.e.classifier.classify(feats);
        v = { ...verdict(r.label, r.target, feats, 'llm'), llm: r };
      } catch (err) {
        v = { ...verdict('content', -1, feats, 'llm-error'), error: err.message };
      }
    }
    if (rec.inFrame) v.inFrame = rec.inFrame;
    rec.verdict = v;
    rec.decidedMs = Math.round(performance.now() - rec.t);
    this.emit({ type: 'candidate', ...rec });
    frame.send({ type: 'verdict', id: msg.id, action: v.action, target: v.target, buttonLabel: v.buttonLabel, inFrame: v.inFrame, source: v.source });
    // Cache decisions that hold for the site; a user-gesture "content" is about that moment only.
    if (frame.isTop && v.source !== 'user-gesture' && v.source !== 'backdrop' && v.source !== 'llm-error') {
      this.e.cache.put(this.topUrl || f.url, { sig: f.sig, label: v.label, action: v.action, buttonLabel: v.buttonLabel, source: v.source });
    }
  }

  applied(msg) {
    const rec = this.candidates.get(msg.id);
    this.emit({ type: 'applied', ...msg, sig: rec?.features.sig });
    // The click did not dismiss it: next time, hide straight away.
    if (rec && msg.fallback === 'hide' && rec.verdict?.source !== 'cache') {
      this.e.cache.put(this.topUrl, { sig: rec.features.sig, label: rec.verdict.label, action: 'hide', source: rec.verdict.source });
    }
  }

  async waitForFrame(origins, ms) {
    for (const end = performance.now() + ms; ; ) {
      const o = origins.find((x) => this.frameSummaries.has(x));
      if (o || performance.now() > end) return o ?? null;
      await new Promise((r) => setTimeout(r, 100));
    }
  }

  site() { return this.topUrl ? siteOf(this.topUrl) : null; }
}
