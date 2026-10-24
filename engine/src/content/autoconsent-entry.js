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
  window.__sereneAutoconsent = {
    /** ac = {config, rules} from the host; onMessage gets autoconsent's own messages. */
    start(ac, onMessage) {
      if (instance) return;
      // Without a config the constructor only asks for one (an 'init' message, ignored here);
      // initialize() then starts it, as autoconsent's own standalone build does.
      instance = new AutoConsent((m) => { try { onMessage(m); } catch {} return Promise.resolve(); });
      instance.initialize({ ...ac.config, isMainWorld: true }, ac.rules);
    },
  };
})();
