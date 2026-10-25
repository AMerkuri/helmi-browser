// Autoconsent rules for each frame: DuckDuckGo's compact CMP rule set, cut down to the rules
// that can run on a given URL (generic rules, plus site-specific ones that match it). On
// Android the rule file is an asset refreshed like the filter lists, and the generic part is
// the same for every page, so it can go into the document-start script once.
import { createRequire } from 'node:module';
import { filterCompactRules } from '@duckduckgo/autoconsent';

const require = createRequire(import.meta.url);

export const AUTOCONSENT_CONFIG = {
  enabled: true,
  autoAction: 'optOut',          // never opt in
  // healthline-media: from a US address there is no consent modal, but the rule's fallback
  // still clicks the first header button, which opens the site menu (crawl, 2026-10-24).
  disabledCmps: ['healthline-media'],
  enablePrehide: true,           // its own early opacity-hide of known CMP containers
  enableCosmeticRules: true,     // CMPs it can only hide, not answer (reported as cosmetic)
  enableGeneratedRules: true,
  enableHeuristicDetection: false, // Serene's own detector covers unknown popups
  enablePopupMutationObserver: false,
  detectRetries: 20,
  prehideTimeout: 2000,
  visualTest: false,
  logs: { lifecycle: false, rulesteps: false, detectionsteps: false, evals: false, errors: false, messages: false, waits: false },
};

export class AutoconsentRules {
  constructor({ compact = require('@duckduckgo/autoconsent/rules/compact-rules.json'), config = AUTOCONSENT_CONFIG } = {}) {
    this.compact = compact;
    this.config = config;
  }

  /** What the frame's content script needs: {config, rules}. */
  forFrame(url, isTop) {
    return { config: this.config, rules: { autoconsent: [], compact: filterCompactRules(this.compact, { url, mainFrame: isTop }) } };
  }
}
