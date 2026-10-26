# Serene engine (prototype)

The hybrid annoyance engine: filter lists → overlay detector + heuristics → on-device LLM, with per-site caching. Design notes and results: [docs/engine.md](../docs/engine.md).

```
src/content/serene.js   content script (document start, every frame): rules CSS, detector, features, actions
src/content/autoconsent-entry.js  entry for dist/autoconsent.js (npm run build): DuckDuckGo autoconsent, injected before serene.js
src/host/rules.mjs      adblock-rust (Brave) network + cosmetic rules
src/host/lists.mjs      filter list download/refresh
src/host/policy.mjs     cache → heuristics → LLM routing, safe-click rules
src/host/classifier.mjs prompt, single-pass label scoring (or grammar-constrained JSON), llama.cpp backend for Qwen3-0.6B
src/host/autoconsent.mjs autoconsent rules + config for each frame
src/host/cache.mjs      per-site verdict cache (eTLD+1 + selector signature)
src/host/engine.mjs     host session per page (what the app's native side will do)
harness/browser.mjs     Playwright stand-in for the WebView hooks
harness/run.mjs         real-site crawl: baseline vs rules vs full engine, report + screenshots
test/                   fixture e2e tests, unit tests, model eval + labelled seed set
```

```sh
npm install && npx playwright install chromium
npm run build            # dist/: autoconsent.js + its rules and config, also packaged by the app (npm test runs it first)
npm test
npm run model && npm run llm-eval
# a .litertlm build, the way the app runs it (pip install litert-lm==0.18.1):
python3 test/litert-eval.py --model Qwen3-0.6B.litertlm
python3 test/litert-eval.py --model Qwen3-0.6B.litertlm --variant no-target --half tune   # a prompt from test/prompt-variants.mjs on every other record
npm run crawl            # or: npm run crawl:no-model
```

Set `SERENE_CHROMIUM=/path/to/chromium` to use an existing Chromium build.
