// Rules layer: Brave's adblock-rust (same crate we will ship over JNI on Android).
import { createRequire } from 'node:module';
import { TRUSTED } from './lists.mjs';
const adblock = createRequire(import.meta.url)('adblock-rs');

export class RulesEngine {
  constructor({ lists, resources }) {
    const filterSet = new adblock.FilterSet(false);
    for (const l of lists) filterSet.addFilters(l.text, { format: 'Standard', permissions: l.trusted ? TRUSTED : 0 });
    this.engine = new adblock.Engine(filterSet);
    if (resources) this.engine.useResources(resources);
    // A second, smaller engine with only the cookie-banner rules tells which of the main
    // engine's hide selectors are consent banners. Exceptions still come from the main engine:
    // only selectors the main engine returns are ever used.
    const consent = lists.map((l) => l.consentText).filter(Boolean);
    if (consent.length) {
      const cs = new adblock.FilterSet(false);
      for (const t of consent) cs.addFilters(t, { format: 'Standard' });
      this.consentEngine = new adblock.Engine(cs);
    }
  }

  /** Network rule check. type is a WebView/Playwright resource type ("script", "sub_frame", ...). */
  shouldBlock(url, sourceUrl, type) {
    try { return this.engine.check(url, sourceUrl, type); } catch { return false; }
  }

  /** Per-URL cosmetic resources, flattened into what the content script needs. */
  cosmetic(url) {
    const r = this.engine.urlCosmeticResources(url);
    const consent = this.consentEngine ? new Set(this.consentEngine.urlCosmeticResources(url).hide_selectors) : new Set();
    return {
      hideSelectors: r.hide_selectors.filter((s) => !consent.has(s)),
      consentSelectors: r.hide_selectors.filter((s) => consent.has(s)),
      procedural: r.procedural_actions.map((s) => JSON.parse(s)),
      exceptions: r.exceptions,
      script: r.injected_script,
      generichide: r.generichide,
    };
  }

  /** Generic class/id rules (`##.cookie-banner`) for the class/id tokens seen in the page. */
  genericSelectors(classes, ids, exceptions) {
    const all = this.engine.hiddenClassIdSelectors(classes, ids, exceptions);
    const consent = this.consentEngine ? new Set(this.consentEngine.hiddenClassIdSelectors(classes, ids, exceptions)) : new Set();
    return { hide: all.filter((s) => !consent.has(s)), consent: all.filter((s) => consent.has(s)) };
  }
}
