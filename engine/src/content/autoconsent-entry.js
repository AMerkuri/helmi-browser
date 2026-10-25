// Entry point for dist/autoconsent.js (npm run build): DuckDuckGo's autoconsent (MPL-2.0),
// which knows how to press "reject" in a few hundred consent tools (OneTrust, Sourcepoint,
// Didomi, Quantcast, Usercentrics, ...) by their own element ids, so it works in any language.
//
// Injected at document start into every frame, before serene.js. It does nothing until
// serene.js hands it the rules and config from the host's init reply. In a WebView the
// document-start script already runs in the page's main world, so autoconsent runs its
// eval snippets inline (isMainWorld) instead of asking the host to evaluate them.
import AutoConsent from '@duckduckgo/autoconsent';

(() => {
  if (window.__sereneAutoconsent) return;
  let instance = null;

  // Autoconsent's own prehide (consent tools' containers at opacity 0 while it works) starts
  // only when the host's init reply arrives, and on a phone the page can paint before that. A
  // banner the site renders in its HTML then shows until the reply (Stack Overflow in the EU,
  // 2026-10-25). So the top frame hides the same containers from document start, the way
  // autoconsent does, and lets go once autoconsent has taken over, the host says it is off
  // for this page, or after its prehideTimeout. The list is built in by build.mjs.
  let early = null;
  if (window === window.top && __SERENE_EARLY_PREHIDE__) {
    early = document.createElement('style');
    early.setAttribute('data-serene', 'early-prehide');
    early.textContent = `${__SERENE_EARLY_PREHIDE__}{opacity:0!important;z-index:-1!important;pointer-events:none!important}`;
    // Before the parser has created <html> there is nowhere to put it yet.
    if (document.documentElement) document.documentElement.appendChild(early);
    else {
      const mo = new MutationObserver(() => {
        if (!document.documentElement) return;
        mo.disconnect();
        if (early) document.documentElement.appendChild(early);
      });
      mo.observe(document, { childList: true });
    }
    setTimeout(() => release(), 2000);
  }
  function release() {
    if (early) { early.remove(); early = null; }
  }

  window.__sereneAutoconsent = {
    /** ac = {config, rules} from the host; onMessage gets autoconsent's own messages. */
    start(ac, onMessage) {
      if (instance) return;
      // Without a config the constructor only asks for one (an 'init' message, ignored here);
      // initialize() then starts it, as autoconsent's own standalone build does.
      try {
        instance = new AutoConsent((m) => { try { onMessage(m); } catch {} return Promise.resolve(); });
        instance.initialize({ ...ac.config, isMainWorld: true }, ac.rules);
      } finally {
        release(); // its own prehide is on now
      }
    },
    /** The host's reply has no autoconsent for this page (site turned off, autoconsent off). */
    release,
  };
})();
