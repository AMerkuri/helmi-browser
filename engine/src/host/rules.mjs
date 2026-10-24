// Rules layer: Brave's adblock-rust (same crate we will ship over JNI on Android).
import { createRequire } from 'node:module';
const adblock = createRequire(import.meta.url)('adblock-rs');

export class RulesEngine {
  constructor({ lists, resources }) {
    const filterSet = new adblock.FilterSet(false);
    for (const l of lists) filterSet.addFilters(l.text, { format: 'Standard' });
    this.engine = new adblock.Engine(filterSet);
    if (resources) this.engine.useResources(resources);
  }

  /** Network rule check. type is a WebView/Playwright resource type ("script", "sub_frame", ...). */
  shouldBlock(url, sourceUrl, type) {
    try { return this.engine.check(url, sourceUrl, type); } catch { return false; }
  }

  /** Per-URL cosmetic resources, flattened into what the content script needs. */
  cosmetic(url) {
    const r = this.engine.urlCosmeticResources(url);
    return {
      hideSelectors: r.hide_selectors,
      procedural: r.procedural_actions.map((s) => JSON.parse(s)),
      exceptions: r.exceptions,
      script: r.injected_script,
      generichide: r.generichide,
    };
  }

  /** Generic class/id rules (`##.cookie-banner`) for the class/id tokens seen in the page. */
  genericSelectors(classes, ids, exceptions) {
    return this.engine.hiddenClassIdSelectors(classes, ids, exceptions);
  }
}
