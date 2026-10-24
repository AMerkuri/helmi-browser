# Serene: hybrid engine prototype

Status: prototype · 2026-10-24 · Step 2 of 4. Code in [`engine/`](../engine). Builds on [decisions.md](decisions.md).

## Summary

- The engine runs in four layers: network rules, then cosmetic rules, then an overlay detector with heuristics, then the LLM for the uncertain cases. Every decision is cached per site.
- The DOM side is one content script, [`engine/src/content/serene.js`](../engine/src/content/serene.js). It is written against the same `SereneBridge` object that `addWebMessageListener` gives us, so it goes into the app unchanged.
- The host side (rules, policy, cache, prompt) is plain JS in [`engine/src/host/`](../engine/src/host). It has no DOM code, so it ports straight to Kotlin in step 3.
- **Tested:** 15 end-to-end fixture tests in Chromium with a Pixel 7 viewport, plus 6 unit tests, all passing. The rules engine builds in about 60 ms, and lookups cost about 0.2 ms per page and about 2 µs per request.
- **Not tested yet:** real sites and the real model. This cloud sandbox blocks ordinary websites and huggingface.co. The crawl and model eval are written and run end to end on local pages. They need one run on a network-enabled environment (see [Running](#running)).

## Flow

```
navigation
  │ request ──► adblock-rust network check ──► block (CMP / annoyance scripts)
  │
  ├─ document start: content script ──hello──► host: cosmetic(url) + cached site verdicts
  │                                   ◄─init── hide CSS, scriptlets, procedural filters, site rules
  ├─ DOMContentLoaded: class/id tokens ─────► host: generic hide selectors (##.cookie-banner)
  │                                   ◄─generic─ (first scan waits for this reply, max 500 ms)
  └─ scan (load, +0.8/2/4/7/11 s, and 250 ms after DOM mutations)
        elementsFromPoint on an 18-point grid → outermost fixed/sticky ancestor = candidate
        cached verdict for its signature? → act locally, no round trip
        features + heuristic score ──candidate──► host policy:
            user tapped < 1.5 s ago            → ignore (user-opened menu or dialog)
            heuristic ≥ 0.85                   → act on the heuristic label
            heuristic < 0.25 / page chrome     → ignore
            in between, or modal-shaped with no keyword hit → LLM
        ◄─verdict── click (decline/close button) | hide | ignore
        verdict cached as (eTLD+1, selector signature)
```

## What each layer does

**Rules.** The lists are Fanboy's Annoyance List (which already includes the EasyList Cookie List), uBO Cookie Notices and uBO Other Annoyances: about 65k lines, 3.1 MB serialized. Brave's `adblock-rs` binds the same adblock-rust crate we will ship over JNI. The content script applies hide CSS, uBO scriptlets, and a subset of procedural filters (`has-text`, `upward`, `min-text-length`, `matches-css`, plus `remove`/`style` actions). The first detector scan waits for the generic class/id rules. Without that wait, the detector raced the lists for banners the lists already cover (the `cookie-onetrust` fixture caught this).

**Detector.** It does not walk the whole DOM. It samples 18 viewport points with `elementsFromPoint` and climbs to the outermost `position: fixed|sticky` ancestor, plus `dialog[open]` and `[aria-modal]`. Anything that covers the reader's view shows up at one of those points. On the fixtures a scan costs under 5 ms; real-page cost is a step 4 measurement. The detector reads open shadow roots, and it reads cross-origin CMP iframes through that frame's own copy of the script (`frameSummary`), which can then click inside the frame.

**Features** sent to the host: about 300 characters of text, up to 8 button labels in order, role/aria, id/class tokens, iframe origins, coverage, screen edge, z-index, scroll lock, email/password/video inputs, a "looks like page layout" flag, and whether the user just tapped. The prompt is under 600 characters, about 100–200 tokens plus the cached system prompt.

**Heuristics.** Keyword sets per label (cookie wording in about 15 languages), a structure score (coverage, scroll lock, `aria-modal`, z-index, a few buttons), and penalties for headers, navigation, video and big text blocks. Clear cases never reach the model. In the rules-only run, 9 of the 11 overlay fixtures were handled without it.

**LLM.** Qwen3-0.6B, zero-shot, with thinking suppressed (an empty `<think>` block plus `/no_think`). The output is constrained by a JSON-schema grammar to `{"label": <one of 9>, "target": -1..7}`, so the reply cannot be malformed. Calls are serialized and reuse the system-prompt KV cache. In the harness it runs on llama.cpp (`node-llama-cpp`, GGUF Q8_0); on the phone it runs on LiteRT-LM with the `.litertlm` build. The prompt and the output contract are the same in both.

**Policy (safety rules)** in [`policy.mjs`](../engine/src/host/policy.mjs):
- We only ever click a button that declines or closes. If the model picks "Accept", "Allow", "Subscribe", "Sign in" or "Open in app", the policy looks for a decline button itself and hides the overlay if there is none. "Continue without accepting" counts as a decline.
- Paywall teasers and login walls are always hidden, never clicked. Only the overlay goes; inline paywalls are not touched.
- Overlays that appear within 1.5 s of a user tap are left alone, and that decision is not cached.
- After a click, if the overlay is still there 900 ms later, it is hidden instead, and the cache is updated to `hide`.
- After hiding, the engine removes the backdrop (an empty full-screen fixed sibling), unlocks `overflow`/`position: fixed` scroll locks (restoring the scroll offset), and removes `filter: blur`. A page that is still scroll-locked with nothing visible on top is unlocked too. That case is what list-hidden banners usually leave behind.

**Cache.** Verdicts are keyed by `(eTLD+1, selector signature)`. Signatures skip hashed class names. Hide verdicts with a usable selector go out as CSS at document start, so the overlay never flashes. Click verdicts store the button *label*, not its index. `undo()` stores an `ignore` rule. On the second visit to a fixture site, the model is not called again (tested).

## Results so far

### Fixtures (synthetic, `npm test`)

Hand-written pages that imitate common patterns. They are not copies of real sites. The model is stubbed in these tests; each fixture states what the model should answer.

| Fixture | Handled by | Outcome |
|---|---|---|
| OneTrust-style bottom banner | rules (generic `#onetrust-banner-sdk`) | hidden before the detector sees it |
| custom cookie bar (unknown class) | heuristics | clicked "Reject all" |
| German consent modal + backdrop + scroll lock | heuristics | clicked "Nur notwendige Cookies", backdrop gone, scrolling restored |
| consent in a cross-origin iframe | model (via frame summary) | clicked "Reject" inside the iframe |
| consent banner in shadow DOM | heuristics | clicked "Reject all" |
| Japanese cookie modal (no keyword hit) | model | hidden, scroll restored; second visit from cache, no model call |
| newsletter modal after 1.5 s, scroll lock | heuristics | clicked "No thanks" |
| newsletter modal locking `body` with `position: fixed` | heuristics | hidden, scrolling restored |
| notification soft prompt | heuristics | clicked "Not now" |
| metered paywall sheet | heuristics | sheet hidden, article untouched, nothing clicked |
| open-in-app bar | heuristics | clicked "Not now" |
| sticky header with "Subscribe" link | none | left alone |
| full-screen menu opened by a tap | none (user gesture) | left alone |
| article about cookies + mini video player | none | left alone |

With the model off (rules + heuristics only), the iframe and Japanese cases stay on screen. Those are exactly the cases the model is for.

### Real sites and the real model: pending

[`harness/sites.json`](../engine/harness/sites.json) lists 30 sites. There are 27 with known overlays: EU and US news, shops with newsletter modals, Reddit/Medium/Quora/Pinterest walls, IMDb and others. There are also 3 controls (Wikipedia, GitHub, DuckDuckGo). The crawl loads each one three ways: a plain browser, rules-only, and the full engine. It then reloads each site to check the second visit. It writes a report, screenshots, and a `candidates.jsonl` of every overlay it saw, with features and verdicts, for labelling. [`test/llm-eval.mjs`](../engine/test/llm-eval.mjs) scores the model on a labelled set. The seed set has 30 records: 12 from the fixtures and 18 hand-written ones in 7 languages, including 5 "content" negatives (cart drawer, audio player, nav drawer, filter sheet, sticky header). It reports accuracy, a confusion table, latency and prompt tokens.

## Running

```sh
cd engine
npm install                 # builds adblock-rust (needs Rust); Node ≥ 22
npx playwright install chromium
npm test                    # fixtures + unit tests
npm run model               # Qwen3-0.6B Q8_0 GGUF, ~640 MB, from huggingface.co
npm run llm-eval            # model accuracy + latency on test/data/labeled-features.jsonl
npm run crawl               # 30 real sites → results/<time>/report.md + shots/
npm run crawl -- --only bbc,spiegel --headed
```

The crawl needs normal web access and huggingface.co, which this project's current cloud environment doesn't have. Run it from a session on a full-network environment, or locally. Which overlays appear depends on the visitor's country (GDPR banners show up for EU IPs), so record where the crawl ran.

## Porting to Android (step 3)

| Prototype | App |
|---|---|
| `page.addInitScript(serene.js)` | `WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))` |
| `exposeBinding('__sereneHost')` + shim | `WebViewCompat.addWebMessageListener(webView, "SereneBridge", setOf("*"), listener)`, replying with `replyProxy.postMessage` |
| `context.route` | `WebViewClient.shouldInterceptRequest` → `RulesEngine.shouldBlock` |
| `rules.mjs` (adblock-rs) | adblock-rust via JNI (`cargo-ndk`); same calls |
| `policy.mjs`, `cache.mjs`, `classifier.mjs` prompt/parse | Kotlin, same logic; cache in Room or DataStore |
| `LlamaClassifier` (llama.cpp) | LiteRT-LM `Engine`/`Session` with constrained decoding; same prompt and schema |

## Known gaps

- **Unmeasured:** the real-site hit rate, false positives, model accuracy and on-device latency. All of these need the crawl and llm-eval runs above, then step 4 on phones.
- When the model labels a long-tail case it cannot click a decline button written in an unknown language (`isSafeTarget` only knows about 15 languages), so it hides the overlay instead. That is the safe fallback, but it leaves the consent undecided.
- Scriptlets are applied after the host replies (async), not strictly before page scripts. That matches what WebView allows without rewriting HTML responses. Some uBO scriptlets lose races because of it.
- Unsupported procedural operators (`xpath`, `matches-path`, `matches-attr`, `:matches-css-before/after`) are skipped.
- Closed shadow roots are opaque: the detector sees only the host element.
- Inline paywalls and in-article newsletter boxes are out of scope on purpose. Only fixed or sticky overlays are handled, plus whatever the lists hide.
- The heuristic thresholds (0.85 act, 0.25 ignore, 0.2 structure → model) are first guesses, to be tuned on the crawl data.
