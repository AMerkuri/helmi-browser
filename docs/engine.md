# Serene: hybrid engine prototype

Status: prototype, tested on real sites · 2026-10-24 · Step 2 of 4. Code in [`engine/`](../engine). Builds on [decisions.md](decisions.md).

## Summary

- The engine runs in four layers: network rules, then cosmetic rules, then an overlay detector with heuristics, then the LLM for the uncertain cases. Every decision is cached per site.
- The DOM side is one content script, [`engine/src/content/serene.js`](../engine/src/content/serene.js). It is written against the same `SereneBridge` object that `addWebMessageListener` gives us, so it goes into the app unchanged.
- The host side (rules, policy, cache, prompt) is plain JS in [`engine/src/host/`](../engine/src/host). It has no DOM code, so it ports straight to Kotlin in step 3.
- **Tested:** 16 end-to-end fixture tests in Chromium with a Pixel 7 viewport, plus 8 unit tests, all passing. The rules engine builds in about 60 ms, and lookups cost about 0.2 ms per page and about 2 µs per request.
- **Real sites (29 reachable of 30):** 13 sites had something on screen in a plain browser (a consent wall, a login wall, a locked page). The full engine cleared 11 of them, broke none, and hid nothing on the 3 control sites. Filter lists and heuristics did almost all of it: the model was asked 4 times in 58 page loads. See [Real sites](#real-sites-2026-10-24).
- **Real model:** Qwen3-0.6B labels 48 of 57 overlays correctly (84%), or 54 of 57 (95%) once site headers are kept away from it, as the engine now does. About 650 ms per call on 4 x86 cores without a GPU.
- The first real-site run found five bugs that the fixtures missed, all fixed here (see [What the first crawl found](#what-the-first-crawl-found)).

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
            thin bar at the top (header/nav)   → ignore, unless cookie wording or a strong score
            heuristic ≥ 0.85                   → act on the heuristic label
            heuristic < 0.25 / page chrome     → ignore
            in between, or modal-shaped with no keyword hit → LLM
        ◄─verdict── click (decline/close button) | hide | ignore
        verdict cached as (eTLD+1, selector signature)
```

## What each layer does

**Rules.** The lists are Fanboy's Annoyance List (which already includes the EasyList Cookie List), uBO Cookie Notices and uBO Other Annoyances: about 65k lines, 3.1 MB serialized. Brave's `adblock-rs` binds the same adblock-rust crate we will ship over JNI. The content script applies hide CSS, uBO scriptlets, and a subset of procedural filters (`has-text`, `upward`, `min-text-length`, `matches-css`, plus `remove`/`style` actions). The first detector scan waits for the generic class/id rules. Without that wait, the detector raced the lists for banners the lists already cover (the `cookie-onetrust` fixture caught this).

**Detector.** It does not walk the whole DOM. It samples 18 viewport points with `elementsFromPoint` and climbs to the outermost `position: fixed|sticky` ancestor, plus `dialog[open]` and `[aria-modal]`. Anything that covers the reader's view shows up at one of those points. On the fixtures a scan costs under 5 ms; real-page cost is a step 4 measurement. The detector reads open shadow roots, and it reads cross-origin CMP iframes through that frame's own copy of the script (`frameSummary`), which can then click inside the frame. The frame also runs the keyword heuristics on its own text, so a consent dialog in an iframe no longer needs the model.

**Features** sent to the host: about 300 characters of text, up to 8 button labels in order, role/aria, id/class tokens, iframe origins, coverage, screen edge, z-index, scroll lock, email/password/video inputs, a "looks like page layout" flag, and whether the user just tapped. The prompt is under 600 characters, about 100–200 tokens plus the cached system prompt.

**Heuristics.** Keyword sets per label (cookie wording in about 15 languages), a structure score (coverage, scroll lock, `aria-modal`, z-index, a few buttons), and penalties for headers, navigation, video and big text blocks. Clear cases never reach the model. In the rules-only run, 9 of the 11 overlay fixtures were handled without it.

**LLM.** Qwen3-0.6B, zero-shot, with thinking suppressed (an empty `<think>` block plus `/no_think`). The output is constrained by a JSON-schema grammar to `{"label": <one of 9>, "target": -1..7}`, so the reply cannot be malformed. Calls are serialized and reuse the system-prompt KV cache. In the harness it runs on llama.cpp (`node-llama-cpp`, GGUF Q8_0); on the phone it runs on LiteRT-LM with the `.litertlm` build. The prompt and the output contract are the same in both.

**Policy (safety rules)** in [`policy.mjs`](../engine/src/host/policy.mjs):
- We only ever click a button that declines or closes. If the model picks "Accept", "Allow", "Subscribe", "Sign in" or "Open in app", the policy looks for a decline button itself and hides the overlay if there is none. "Continue without accepting" counts as a decline.
- Paywall teasers and login walls are always hidden, never clicked. Only the overlay goes; inline paywalls are not touched.
- Overlays that appear within 1.5 s of a user tap are left alone, and that decision is not cached.
- Thin bars at the top edge are page chrome: under 15% of the screen, plus a `<header>`/`<nav>` tag, a header/nav class, or under 60 characters of text. They never go to the model. On one that is not tagged as a header, only cookie wording or a very strong heuristic score leads to an action.
- "Reject and subscribe" buttons on pay-or-consent walls are not declines.
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
| consent in a cross-origin iframe | heuristics (on the frame's own text) | clicked "Reject" inside the iframe |
| consent banner in shadow DOM | heuristics | clicked "Reject all" |
| Japanese cookie modal (no keyword hit) | model | hidden, scroll restored; second visit from cache, no model call |
| cookie bar on a slow page (host reply arrives before `<html>` exists) | heuristics | clicked "Reject all" (regression test for a real-site bug) |
| newsletter modal after 1.5 s, scroll lock | heuristics | clicked "No thanks" |
| newsletter modal locking `body` with `position: fixed` | heuristics | hidden, scrolling restored |
| notification soft prompt | heuristics | clicked "Not now" |
| metered paywall sheet | heuristics | sheet hidden, article untouched, nothing clicked |
| open-in-app bar | heuristics | clicked "Not now" |
| sticky header with "Subscribe" link | none | left alone |
| full-screen menu opened by a tap | none (user gesture) | left alone |
| article about cookies + mini video player | none | left alone |

With the model off (rules + heuristics only), the Japanese case stays on screen. That is exactly the case the model is for.

### Real sites (2026-10-24)

**Setup.** A cloud container in Columbus, Ohio (US IP address), headless Chromium 1243 with Pixel 7 emulation, locale `en-GB`, a 10 s wait per page, and Qwen3-0.6B Q8_0 on llama.cpp with 4 x86 cores and no GPU. Each site loads three ways (plain browser, rules + heuristics, full engine) and then reloads once. Even from a US address, the EU sites showed their consent walls (Spiegel, Le Monde, El País, Corriere, Onet, iDNES), and US sites showed US privacy notices (CNN, the Guardian). Full reports: [before the fixes](results/2026-10-24/crawl-1-before-fixes.md), [after the fixes](results/2026-10-24/crawl-2-after-fixes.md). Screenshots, plain browser on top and Serene below: [before-after.jpg](results/2026-10-24/before-after.jpg).

**After the fixes** (second crawl). The Washington Post refused connections from the container, which leaves 29 sites. Of those, 13 had something on screen in the plain browser: an annoyance, an overlay covering 30% or more of the screen, or a locked page.

| Site | Plain browser | Serene (first visit) | Handled by |
|---|---|---|---|
| The Guardian | US privacy notice | closed with "×" | heuristics |
| Spiegel | pay-or-consent wall in an iframe, page locked | wall hidden, scrolling restored, header kept | heuristics on the iframe's text |
| Le Monde | cookie wall, page locked | gone | filter lists |
| Corriere | pay-or-consent wall ("Rifiuta e abbonati"), locked | hidden, nothing clicked | heuristics |
| Onet | consent popup, locked | gone | filter lists |
| CNN | "Legal Terms and Privacy" modal, locked | hidden | heuristics |
| Stack Overflow | OneTrust banner | gone | filter lists |
| Pinterest | sign-up wall, locked | hidden, scrolling restored | heuristics |
| nu.nl, Reddit, Allbirds | page locked, or a consent banner | unlocked, banner gone | filter lists |
| Gymshark | OneTrust banner, plus a "15% off" bar at the top | banner gone; the top bar stays (the model calls it content) | lists, model |
| DuckDuckGo (control) | box promoting its own browser | left alone | none |

- Nothing was hidden by mistake. All three control sites and every site header were left alone. BBC also got a survey popup in one load, and the engine closed it.
- Second visits: no annoyances on any site. The model ran twice, both times for overlays that showed up only on the second load.
- The model was asked 4 times in 58 page loads. Each call took 1.8–14 s (1.8, 4.2, 5.1 and 13.9 s) while the browser was loading pages on the same 4 cores. On an idle CPU the same calls take about 0.65 s (see below), so this is contention, but it shows the model has to stay off the critical path. The four calls were Onet's bottom nav bar (content), Gymshark's promo bar (content), the iDNES "close ad" sticky ad (labelled cookie consent by mistake, but the click on "Zavřít reklamu" is harmless) and weather.com's "Get the App" pill (app install, clicked "Dismiss").
- The first action came 1.6–4.6 s after navigation, so a banner can show for a moment before it goes. Lists hide theirs before first paint. That gap is a step 4 item.

### What the first crawl found

The first crawl ran the PR #2 code unchanged. It found five bugs, all fixed in this change:

1. **The engine sometimes never started on a page** (7 of 58 loads with rules on: Guardian, BBC, Corriere twice, CNN, Reddit, Gymshark). If the page's first bytes are slow, the host's reply arrives before `<html>` exists. Inserting the rules CSS then threw, and the detector never started. The CSS now waits for the document, and the detector starts regardless. This is covered by a new fixture with a slow server.
2. **The model hid site headers.** It labelled the Stack Overflow header ("Log in Sign up") and the Spiegel header ("Anmelden") as login walls and hid them. The heuristics hid Healthline's sticky nav ("Subscribe") as a newsletter. Thin bars at the top now count as page chrome (see the policy above).
3. **The engine clicked "Rifiuta e abbonati" on Corriere**, which means reject *and subscribe*. Subscription and payment words in seven languages now make a button unsafe to click. Pay-or-consent walls are hidden instead.
4. **Pinterest stayed scroll-locked** after its wall was hidden. Its backdrop is not a sibling of the modal, so it survived and kept the page "blocked". Backdrops that the detector saw as their own candidates are now hidden with their modal. An empty full-screen layer with no modal on it is hidden too, once the page is locked or after 2 s.
5. **Consent dialogs inside iframes needed the model** (Spiegel's Sourcepoint wall), so rules-only mode left them up. The iframe now runs the keyword heuristics on its own text.

The eval set also had the Japanese and German fixture texts double-encoded (UTF-8 read as Windows-1252). The Japanese "unknown language" case was unreadable to the model, and is now fixed.

### Model accuracy

[`test/llm-eval.mjs`](../engine/test/llm-eval.mjs) on 57 labelled records: the 30-record seed set plus 27 overlays from the crawl, labelled by hand. Log: [llm-eval.txt](results/2026-10-24/llm-eval.txt).

| | PR #2 prompt | Tuned prompt |
|---|---|---|
| Label accuracy | 81% (46/57) | **84% (48/57)** |
| Counting the header guard (what the engine does) | n/a | **95% (54/57)** |
| Annoyances not mistaken for content (37 records) | 35 | 37 |
| Invalid replies | 0 | 0 |
| Median latency, 4 x86 cores, no GPU | 630 ms | 650 ms |
| Prompt tokens (system + features) | 239 | 283 |

- The tuned prompt fixes paywall-vs-newsletter mix-ups (both paywalls came back as newsletters before) and small app banners. Its remaining errors are almost all site headers. The model reads "Log in" or "Subscribe" in a nav bar as a wall, and the header guard keeps those away from it. Prompt variants that told it to prefer "content" when unsure made it miss real cookie walls (79%).
- Errors left after the guard: an article with a video player (labelled app install, but the heuristics never send that to the model), a top "Open in app" bar (the guard ignores it), and the Pinterest wall (labelled cookie consent instead of login wall; it is hidden either way).
- The model's `target` (which button to press) is close to useless. It picked an "accept" or "subscribe" button 16 times. The policy overrules those every time, and it only clicks buttons that match its own decline list. So in practice the button choice comes from the regex, not the model. Dropping `target` from the output, or asking the model only to rank decline candidates, is a step 4 option.
- 650 ms on a desktop CPU means little for phones. LiteRT-LM on a phone NPU/GPU is the number that matters (step 4). Even on a desktop CPU, though, the call takes long enough that the overlay is visibly on screen meanwhile. Quarantining (hiding while deciding) already covers scores ≥ 0.6.

## Running

```sh
cd engine
npm install                 # builds adblock-rust (needs Rust); Node ≥ 22
npx playwright install chromium
npm test                    # fixtures + unit tests (about 1 minute)
npm run model               # Qwen3-0.6B Q8_0 GGUF, ~640 MB, from huggingface.co
npm run llm-eval            # model accuracy + latency on test/data/labeled-features.jsonl
npm run crawl               # 30 real sites → results/<time>/report.md + shots/
npm run crawl -- --only bbc,spiegel --headed
```

The crawl needs normal web access and huggingface.co, so it needs a full-network environment or a local machine. A run takes about 25 minutes. Set `SERENE_DEBUG=1` to log every host-to-page message. Which overlays appear depends on the visitor's country (GDPR banners show up for EU IPs), so record where the crawl ran.

## Porting to Android (step 3)

Done in [android/](../android); its README maps each piece below to the Kotlin file that replaced it.

| Prototype | App |
|---|---|
| `page.addInitScript(serene.js)` | `WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))` |
| `exposeBinding('__sereneHost')` + shim | `WebViewCompat.addWebMessageListener(webView, "SereneBridge", setOf("*"), listener)`, replying with `replyProxy.postMessage` |
| `context.route` | `WebViewClient.shouldInterceptRequest` → `RulesEngine.shouldBlock` |
| `rules.mjs` (adblock-rs) | adblock-rust via JNI (`cargo-ndk`); same calls |
| `policy.mjs`, `cache.mjs`, `classifier.mjs` prompt/parse | Kotlin, same logic; cache in Room or DataStore |
| `LlamaClassifier` (llama.cpp) | LiteRT-LM `Engine`/`Session` with constrained decoding; same prompt and schema |

## Known gaps

- **Unmeasured:** on-device latency and battery (step 4 on phones), and the crawl from an EU address. The real-site numbers above come from 29 sites and one US location. That is enough to find bugs, but too few to tune thresholds on.
- Decline buttons are matched by a word list in about 20 languages, now including Japanese, Chinese, Korean, Russian, Turkish and the Nordic languages. A decline button in any other language cannot be clicked, so the overlay is hidden instead. That is the safe fallback, but it leaves the consent undecided.
- The model's button choice (`target`) is not used in practice (see [Model accuracy](#model-accuracy)).
- An overlay can be on screen for 1.6–4.6 s before the engine acts on it, unless the lists cover it. Scanning sooner, or quarantining more eagerly, is a step 4 item.
- The crawl's "annoyances" count comes from the detector's own heuristics. It over-counts headers that contain "Log in Sign up" (Pinterest), so check the screenshots.
- Scriptlets are applied after the host replies (async), not strictly before page scripts. That matches what WebView allows without rewriting HTML responses. Some uBO scriptlets lose races because of it.
- Unsupported procedural operators (`xpath`, `matches-path`, `matches-attr`, `:matches-css-before/after`) are skipped.
- Closed shadow roots are opaque: the detector sees only the host element.
- Inline paywalls and in-article newsletter boxes are out of scope on purpose. Only fixed or sticky overlays are handled, plus whatever the lists hide.
- The heuristic thresholds (0.85 act, 0.25 ignore, 0.2 structure → model) held up on the crawl, but 29 sites is too few to tune them.
