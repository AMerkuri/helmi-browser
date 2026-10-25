# Serene engine (prototype)

The hybrid annoyance engine: filter lists → overlay detector + heuristics → on-device LLM, with per-site caching. Design notes and results: [docs/engine.md](../docs/engine.md).

```
src/content/serene.js   content script (document start, every frame): rules CSS, detector, features, actions
src/content/autoconsent-entry.js  entry for dist/autoconsent.js (npm run build): DuckDuckGo autoconsent, injected before serene.js
src/content/reader-entry.js  entry for dist/reader.js: Mozilla Readability, run on demand for reader mode and summaries
src/host/rules.mjs      adblock-rust (Brave) network + cosmetic rules
src/host/lists.mjs      filter list download/refresh
src/host/policy.mjs     cache → heuristics → LLM routing, safe-click rules
src/host/classifier.mjs prompt, single-pass label scoring (or grammar-constrained JSON), llama.cpp backend for Qwen3-0.6B
src/host/autoconsent.mjs autoconsent rules + config for each frame
src/host/cache.mjs      per-site verdict cache (eTLD+1 + selector signature)
src/host/engine.mjs     host session per page (what the app's native side will do)
src/host/summary.mjs    page summaries with the model: prompts, chunking, reply parsing (docs/reader.md)
harness/browser.mjs     Playwright stand-in for the WebView hooks
harness/run.mjs         real-site crawl: baseline vs rules vs full engine, report + screenshots
test/                   fixture e2e tests, unit tests, model eval + labelled seed set
```

```sh
npm install && npx playwright install chromium
npm run build            # dist/: autoconsent.js + its rules and config, reader.js, also packaged by the app (npm test runs it first)
npm test
npm run model && npm run llm-eval
node test/summary-eval.mjs --model models/Qwen3-0.6B-Q8_0.gguf --pages pages.json   # summaries of saved reader extracts
npm run crawl            # or: npm run crawl:no-model
```

Set `SERENE_CHROMIUM=/path/to/chromium` to use an existing Chromium build.
