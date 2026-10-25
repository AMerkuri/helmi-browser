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

// Consent tools' own hosts. The filter lists block most of them, which means their banner never
// shows and no "reject" is ever recorded (no answer at all, which US sites read as consent).
// With autoconsent on, these load so autoconsent can opt out; their banners stay parked
// (invisible) meanwhile. On the 2026-10-25 crawl this added real opt-outs on Stack Overflow and
// Gymshark (OneTrust) with nothing visible to the reader. All of them are tools autoconsent has rules for.
export const CONSENT_TOOL_HOSTS = /(^|\.)(cookielaw\.org|onetrust\.com|cookiebot\.(com|eu)|usercentrics\.(eu|com)|didomi\.io|privacy-center\.org|privacy-mgmt\.com|sp-prod\.net|consensu\.org|quantcast\.com|inmobi\.com|trustarc\.com|truste\.com|consentmanager\.(net|de)|iubenda\.com|cookieyes\.com|osano\.com|termly\.io|cookie-script\.com|axeptio\.eu|cookiefirst\.com|privacymanager\.io|fundingchoicesmessages\.google\.com|sirdata\.(com|net)|ketchcdn\.com|clickio\.com|uniconsent\.com)$/;

export function isConsentTool(url) {
  try { return CONSENT_TOOL_HOSTS.test(new URL(url).hostname); } catch { return false; }
}

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
