# Serene: hybrid engine prototype

Status: prototype, tested on real sites · 2026-10-24 (updated the same day: autoconsent and single-pass label scoring; 2026-10-25: step 4 measurements, see [Measure and tune](#measure-and-tune-step-4-2026-10-25)) · Steps 2 and 4 of 4. Code in [`engine/`](../engine). Builds on [decisions.md](decisions.md).

## Summary

- The engine runs in four layers: network rules, then cosmetic rules, then an overlay detector with heuristics, then the LLM for the uncertain cases. Every decision is cached per site.
- The DOM side is one content script, [`engine/src/content/serene.js`](../engine/src/content/serene.js). It is written against the same `SereneBridge` object that `addWebMessageListener` gives us, so it goes into the app unchanged.
- The host side (rules, policy, cache, prompt) is plain JS in [`engine/src/host/`](../engine/src/host). It has no DOM code, so it ports straight to Kotlin in step 3.
- **Tested:** 18 end-to-end fixture tests in Chromium with a Pixel 7 viewport, plus 10 unit tests, all passing. The rules engine builds in about 60 ms, and lookups cost about 0.2 ms per page and about 2 µs per request.
- **Real sites (29 reachable of 30):** 13 sites had something on screen in a plain browser (a consent wall, a login wall, a locked page). The full engine cleared 11 of them, broke none, and hid nothing on the 3 control sites. Filter lists and heuristics did almost all of it: the model was asked 4 times in 58 page loads. See [Real sites](#real-sites-2026-10-24).
- **Real model:** Qwen3-0.6B now scores all nine labels in one forward pass instead of writing a JSON answer. That takes 231 ms per call instead of 733 ms on 4 x86 cores without a GPU, and it gives a probability per label. With the header guard and a new confidence floor, 55 of 57 overlays come out right (96%, up from 95%). See [Single-pass label scoring](#single-pass-label-scoring).
- **Cookie banners get a real "reject" in any language** when DuckDuckGo's autoconsent rules know the consent tool: they press the tool's own reject control by its id, not by its text. Banners the filter lists match are parked (invisible but still there) while autoconsent works, and hidden after. See [What each layer does](#what-each-layer-does) and [the third crawl](#autoconsent-on-real-sites-third-crawl).
- The first real-site run found five bugs that the fixtures missed, all fixed here (see [What the first crawl found](#what-the-first-crawl-found)).
- **Exposure, measured** (step 4): an in-page probe now times how long each overlay is actually on screen. Overlays the engine handles are visible for 0–250 ms (worst case before this change: 1.4 s). Consent tools' scripts now load when autoconsent is on, which adds real opt-outs on OneTrust sites. See [Measure and tune](#measure-and-tune-step-4-2026-10-25).

## Flow

```
navigation
  │ request ──► adblock-rust network check ──► block (CMP / annoyance scripts)
  │
  ├─ document start: content script ──hello──► host: cosmetic(url) + cached site verdicts
  │                                   ◄─init── hide CSS, consent selectors, scriptlets, procedural
  │                                            filters, site rules, autoconsent rules for this frame
  ├─ autoconsent (every frame): finds a known consent tool → presses its reject → reports back;
  │      list-matched cookie banners stay parked (opacity 0, click-through) until it is done
  ├─ DOMContentLoaded: class/id tokens ─────► host: generic hide selectors (##.cookie-banner)
  │                                   ◄─generic─ (first scan waits for this reply, max 500 ms)
  └─ scan (DOMContentLoaded +0.3/0.8/2/4/7/11 s, load, transition/animation ends, and at most
        100 ms after DOM mutations: throttled, not debounced, longer when a scan is costly)
        elementsFromPoint on an 18-point grid → outermost fixed/sticky ancestor = candidate
        (skipped while autoconsent is answering a popup in this frame, max 5 s)
        cached verdict for its signature? → act locally, no round trip
        features + heuristic score ──candidate──► host policy:
            user tapped < 1.5 s ago            → ignore (user-opened menu or dialog)
            thin bar at the top (header/nav)   → ignore, unless cookie wording or a strong score
            heuristic ≥ 0.85                   → act on the heuristic label
            heuristic < 0.25 / page chrome     → ignore
            in between, or modal-shaped with no keyword hit → LLM (label scores, one pass)
            LLM's annoyance label below 0.6 confidence → ignore
        ◄─verdict── click (decline/close button) | hide | ignore
        verdict cached as (eTLD+1, selector signature)
```

## What each layer does

**Rules.** The lists are Fanboy's Annoyance List (which already includes the EasyList Cookie List), uBO Cookie Notices, uBO Other Annoyances, and for "open in app" banners AdGuard Mobile App Banners (its uBO-syntax build) and Fanboy's Mobile Notifications: about 72k lines, 3.6 MB serialized. The two app-banner lists were added after a crawl of the 29 sites in `harness/sites.json` (October 2026): on those pages the only visible element they hid that the other lists did not was reddit's "Open App" button. Brave's `adblock-rs` binds the same adblock-rust crate we will ship over JNI. The content script applies hide CSS, uBO scriptlets, and a subset of procedural filters (`has-text`, `upward`, `min-text-length`, `matches-css`, plus `remove`/`style` actions). The first detector scan waits for the generic class/id rules. Without that wait, the detector raced the lists for banners the lists already cover (the `cookie-onetrust` fixture caught this).

*Scriptlets.* The `##+js(...)` rules need uBO's scriptlet code. Brave's `adblock-resources/dist/resources.json`, which Serene used to download, holds only Brave's own resources, so with it no uBO scriptlet ran. `npm run build` now writes `dist/scriptlets.json` from a pinned uBO release (`UBO_VERSION` in `build.mjs`): the functions uBO registers in `src/js/resources` are read with a parser, never run at build time, and become function-style adblock-rust resources, plus uBO's stand-in files (`noeval.js`, `nofab.js`, ...). The app ships the file. Scriptlets uBO marks `requiresTrust` (`trusted-click-element`, `trusted-set-cookie`, ...) carry permission bit 0, and only uBO's own lists are parsed with it, the way uBO trusts only its own lists. uBO Cookie Notices alone has about 2,000 `trusted-click-element` rules that press a site's reject button. The content script runs the scripts inside a function with the `scriptletGlobals` object uBO's injector provides, so the helpers stay off `window` (the `scriptlet-click` fixture checks the whole path). Bump `UBO_VERSION` with uBO stable: a rule that names a newer scriptlet does nothing until then, and `test/rules.test.mjs` fails when one appears.

**Autoconsent.** [DuckDuckGo's autoconsent](https://github.com/duckduckgo/autoconsent) (MPL-2.0, the library DuckDuckGo's own browsers use) knows a few hundred consent tools: OneTrust, Sourcepoint, Didomi, Quantcast, Usercentrics, TrustArc, Cookiebot and many site-specific ones. For each it knows how to tell that a popup is showing and which controls make up an opt-out, by element id or structure, not by button text. That is what makes it work in Greek, Hungarian, Arabic or any other language our decline word list lacks. [`autoconsent-entry.js`](../engine/src/content/autoconsent-entry.js) is bundled into `dist/autoconsent.js` (93 KB, `npm run build`) and injected before `serene.js` in every frame. The host ([`autoconsent.mjs`](../engine/src/host/autoconsent.mjs)) sends each frame the rules that can run on its URL (about 160 KB of JSON for a top frame, mostly generic rules; a few KB for an iframe) in the init reply, with `autoAction: 'optOut'`, so it never opts in. In a WebView the document-start script runs in the page's main world, so autoconsent's eval snippets run inline. Two things keep it and the rest of the engine out of each other's way:
- *Parking.* Autoconsent only acts on a popup it can see, and `display: none` from the lists would hide it from those checks. So the rules engine now has a second, small adblock engine with just the cookie rules (uBO Cookie Notices plus the EasyList Cookie sections of Fanboy's list), which marks which hide selectors are cookie banners. Those get `opacity: 0; pointer-events: none` instead: invisible and click-through, but still laid out. When autoconsent finishes with the frame (or after 10 s) they switch to `display: none`.
- *Cookie banners the detector finds.* A banner the heuristics or the model would hide (`cookie_consent`, no decline button, as on techspot's Quantcast dialog) is parked the same way while autoconsent is at work in the frame, so autoconsent can still find it and reject it. If autoconsent has not found a popup within 1 s it is hidden as before; if it rejects it, the tool removes it. Cached `cookie_consent` hide verdicts go out as parked consent selectors instead of `display: none` CSS, with the same 1 s deadline, so a later visit gets the reject too.
- *Holding the detector.* While autoconsent is answering a popup in the top frame (from "popup found" to its opt-out result, at most 5 s), the detector does not scan, then scans again. For a consent dialog in an iframe, the host waits for that frame's autoconsent result and leaves the overlay alone if it succeeded.
- *Counting.* A parked banner is still laid out, so the content script can tell that one was really on the page: the first time a scan (or the release) finds a cookie-selector match with a box, it sends the host `listHit`. The app counts it for the totals on its start page; it is the only trace of a banner the lists hid, since `display: none` leaves nothing to measure.

**Detector.** It does not walk the whole DOM. It samples 18 viewport points with `elementsFromPoint` and climbs to the outermost `position: fixed|sticky` ancestor, plus `dialog[open]` and `[aria-modal]`. Anything that covers the reader's view shows up at one of those points. On the fixtures a scan costs under 5 ms; on big real pages 5–30 ms on a desktop core (see [step 4](#how-long-overlays-stay-on-screen)). The detector reads open shadow roots, and it reads cross-origin CMP iframes through that frame's own copy of the script (`frameSummary`), which can then click inside the frame. The frame also runs the keyword heuristics on its own text, so a consent dialog in an iframe no longer needs the model.

**Features** sent to the host: about 300 characters of text, up to 8 button labels in order, role/aria, id/class tokens, iframe origins, coverage, screen edge, z-index, scroll lock, email/password/video inputs, a "looks like page layout" flag, and whether the user just tapped. The prompt is under 600 characters, about 100–200 tokens plus the cached system prompt.

**Heuristics.** Keyword sets per label (cookie wording in about 15 languages), a structure score (coverage, scroll lock, `aria-modal`, z-index, a few buttons), and penalties for headers, navigation, video and big text blocks. Clear cases never reach the model. In the rules-only run, 9 of the 11 overlay fixtures were handled without it.

**LLM.** Qwen3-0.6B, zero-shot, with thinking suppressed (an empty `<think>` block). It does not write an answer any more. The engine prefills the prompt plus the start of the answer, `{"label": "`, and reads the model's next-token logits for the first token of each of the nine labels. All nine first tokens differ (`cookie`, `newsletter`, `notification`, `pay`, `app`, `login`, `chat`, `survey`, `content`; checked at load), so that one forward pass decides the label. A softmax over those nine logits gives a probability per label, and the policy uses it (see below). The model no longer picks a button: the policy's decline list always did that in practice. The old grammar-constrained JSON mode is still there (`--mode generate`) for comparison. Calls are serialized and reuse the system-prompt KV cache. In the harness it runs on llama.cpp (`node-llama-cpp`, GGUF Q8_0); on the phone it runs on LiteRT-LM with the `.litertlm` build, which gives no logits: there the model writes its reply freely and the app stops it once the label is written (see [The model on the phone](#the-model-on-the-phone)). The prompt is the same in both.

**Policy (safety rules)** in [`policy.mjs`](../engine/src/host/policy.mjs):
- We only ever click a button that declines or closes. If the model picks "Accept", "Allow", "Subscribe", "Sign in" or "Open in app", the policy looks for a decline button itself and hides the overlay if there is none. "Continue without accepting" counts as a decline.
- Paywall teasers and login walls are always hidden, never clicked. Only the overlay goes; inline paywalls are not touched.
- Overlays that appear within 1.5 s of a user tap are left alone, and that decision is not cached.
- If the model's top label is an annoyance but its probability is under 0.6, the overlay is left alone (`llm-unsure`). On the eval set every correct annoyance label scored 0.82 or more, and most wrong ones (a header, an audio player or a sticky nav read as an app banner or a wall) scored 0.56 or less.
- Thin bars at the top edge are page chrome: under 15% of the screen, plus a `<header>`/`<nav>` tag, a header/nav class, or under 60 characters of text. They never go to the model. On one that is not tagged as a header, only cookie wording or a very strong heuristic score leads to an action.
- "Reject and subscribe" buttons on pay-or-consent walls are not declines.
- After a click, if the overlay is still there 900 ms later, it is hidden instead, and the cache is updated to `hide`.
- After hiding, the engine removes the backdrop (an empty full-screen fixed sibling), unlocks `overflow`/`position: fixed` scroll locks (restoring the scroll offset), and removes `filter: blur`. A page that is still scroll-locked with nothing visible on top is unlocked too. That case is what list-hidden banners usually leave behind.
- A hidden dialog's focus trap (listeners on the document that cancel every touch and click outside the dialog) outlives the dialog, so the page shows but won't scroll. The engine sends Escape, which is how such traps let go: once when it hides a modal, and again if a touch is cancelled later with a dialog hidden and no other modal up.
- On a later visit a cached hide verdict hides the dialog with CSS before the detector sees it. Elements matched by cached hide rules count as hidden too, so the dialog's dimmed wrapper is still cleared at once (which also sends Escape and keeps the page unlocked), and a cancelled touch still lets go of the trap.

**Cache.** Verdicts are keyed by `(eTLD+1, selector signature)`. Signatures skip hashed class names. Hide verdicts with a usable selector go out as CSS at document start, so the overlay never flashes. Click verdicts store the button *label*, not its index. `undo()` stores an `ignore` rule (with the label it replaced); the site sheet lists these under "Shown on this site because you pressed Undo" with Hide again, which removes the rule so the next load judges the element afresh. On the second visit to a fixture site, the model is not called again (tested).

## Results so far

### Fixtures (synthetic, `npm test`)

Hand-written pages that imitate common patterns. They are not copies of real sites. The model is stubbed in these tests; each fixture states what the model should answer.

| Fixture | Handled by | Outcome |
|---|---|---|
| OneTrust-style bottom banner | rules (generic `#onetrust-banner-sdk`) | hidden before the detector sees it |
| OneTrust banner in Greek, with OneTrust's own button ids | autoconsent | clicked "Απόρριψη όλων" (reject all); without autoconsent the lists only hide it (tested both ways) |
| custom cookie bar (unknown class) | heuristics | clicked "Reject all" |
| German consent modal + backdrop + scroll lock | heuristics | clicked "Nur notwendige Cookies", backdrop gone, scrolling restored |
| consent dialog in a dimmed wrapper that fades in after it (no decline button) | heuristics | dialog hidden, then the empty wrapper as soon as it shows; nothing clicked |
| the same while the user keeps touching the page, then a scroll lock added through the CSSOM | heuristics | wrapper hidden despite the touches; the next touch unlocks scrolling |
| consent dialog with a focus trap that cancels touches outside it, also one that starts after the dialog is hidden | heuristics | dialog hidden, nothing clicked; the trap lets go, so a tap on the page reaches it (the late trap: the second tap) |
| consent dialog laid out the way autoconsent's Quantcast rule expects, "More options" and "Agree" on the first layer | heuristics, then autoconsent | parked, not hidden; autoconsent opens the second layer and clicks "Reject all"; on a second visit with the cache, the same |
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
- The first action came 1.6–4.6 s after navigation, so a banner can show for a moment before it goes. Lists hide theirs before first paint. Step 4 showed that most of that time is page load before the banner exists; see [How long overlays stay on screen](#how-long-overlays-stay-on-screen).

### What the first crawl found

The first crawl ran the PR #2 code unchanged. It found five bugs, all fixed in this change:

1. **The engine sometimes never started on a page** (7 of 58 loads with rules on: Guardian, BBC, Corriere twice, CNN, Reddit, Gymshark). If the page's first bytes are slow, the host's reply arrives before `<html>` exists. Inserting the rules CSS then threw, and the detector never started. The CSS now waits for the document, and the detector starts regardless. This is covered by a new fixture with a slow server.
2. **The model hid site headers.** It labelled the Stack Overflow header ("Log in Sign up") and the Spiegel header ("Anmelden") as login walls and hid them. The heuristics hid Healthline's sticky nav ("Subscribe") as a newsletter. Thin bars at the top now count as page chrome (see the policy above).
3. **The engine clicked "Rifiuta e abbonati" on Corriere**, which means reject *and subscribe*. Subscription and payment words in seven languages now make a button unsafe to click. Pay-or-consent walls are hidden instead.
4. **Pinterest stayed scroll-locked** after its wall was hidden. Its backdrop is not a sibling of the modal, so it survived and kept the page "blocked". Backdrops that the detector saw as their own candidates are now hidden with their modal. An empty full-screen layer with no modal on it is hidden too, once the page is locked or after 2 s.
5. **Consent dialogs inside iframes needed the model** (Spiegel's Sourcepoint wall), so rules-only mode left them up. The iframe now runs the keyword heuristics on its own text.

The eval set also had the Japanese and German fixture texts double-encoded (UTF-8 read as Windows-1252). The Japanese "unknown language" case was unreadable to the model, and is now fixed.

### Autoconsent on real sites (third crawl)

The same 30 sites, same container (US address), twice: with autoconsent and without it. Reports: [with](results/2026-10-24/crawl-3-autoconsent-on.md), [without](results/2026-10-24/crawl-3-autoconsent-off.md).

| Site | Without autoconsent | With autoconsent |
|---|---|---|
| The Guardian | US notice closed by the detector ("Closer") | Sourcepoint answered in its iframe: a real opt-out, nothing for the detector to do |
| nu.nl | DPG Media consent wall hidden by the lists | DPG Media rule opted out |
| Spiegel | pay-or-consent wall hidden by the heuristics | hidden by autoconsent's Sourcepoint rule; it correctly does not reject, because rejecting there means paying |
| Healthline | nothing to do | **wrong:** its `healthline-media` rule found no modal but still clicked the first header button, which opened the site menu. That rule is now disabled; rechecked twice, the page stays clean |
| the other 25 | same result both ways | same result both ways |

- Nothing else changed: the same residual overlays (Gymshark's top promo bar, the Pinterest wall that the crawl's heuristics over-count), no new false positives on the control sites, and 4 model calls in 58 loads both ways.
- The surprise: on most sites with a big consent tool, OneTrust on Stack Overflow and Gymshark for example, autoconsent never saw a banner. The filter lists block the consent tool's script (`cookielaw.org`, `otSDKStub`) at the network level, so no banner and no consent prompt is ever shown. No consent is given, but no "reject" is recorded either. Whether to let those scripts load so that autoconsent can answer them was a step 4 question: they now load when autoconsent is on (see [Consent tools' scripts](#consent-tools-scripts)).
- weather.com's first action varied between 4.2 and 11.4 s across runs with and without autoconsent; that is the page, not the engine.

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
- The model's `target` (which button to press) is close to useless. It picked an "accept" or "subscribe" button 16 times. The policy overrules those every time, and it only clicks buttons that match its own decline list. So in practice the button choice comes from the regex, not the model. Step 4 keeps it in the prompt and stops decoding after the label instead (see [The model on the phone](#the-model-on-the-phone)).
- 650 ms on a desktop CPU means little for phones. LiteRT-LM on a phone NPU/GPU is the number that matters (step 4). Even on a desktop CPU, though, the call takes long enough that the overlay is visibly on screen meanwhile. Quarantining (hiding while deciding) already covers scores ≥ 0.6.

### Single-pass label scoring

Same 57 records, same machine (4 x86 cores, no GPU), same prompt. Logs: [scoring](results/2026-10-24/llm-eval-scoring.txt), [generate](results/2026-10-24/llm-eval-generate.txt).

| | Generate JSON (before) | Score labels in one pass (now) |
|---|---|---|
| Label accuracy | 84% (48/57) | 82% (47/57) |
| With the header guard | 95% (54/57) | 93% (53/57) |
| With the guard and the 0.6 confidence floor | n/a | **96% (55/57)** |
| Median latency | 733 ms | **231 ms** |
| p95 latency | 901 ms | 390 ms |
| Invalid replies | 0 | 0 (cannot happen) |

| Confidence | Records | Correct |
|---|---|---|
| 0.9–1.0 | 41 | 93% |
| 0.7–0.9 | 6 | 83% |
| 0.5–0.7 | 7 | 43% |
| under 0.5 | 3 | 33% |

- The speed-up is the decode step going away. The cost is now one prefill of about 300 tokens, most of it the cached system prompt.
- Raw accuracy is one record lower: the audio-player record flips to "app install" at p = 0.50. The confidence floor turns that and four other low-confidence mistakes (a header, the article that mentions cookies, the Reddit and Healthline bars) into "leave alone". The two errors left are the Pinterest wall read as a cookie wall (p = 0.53, now left alone instead of hidden as the wrong kind) and Medium's sidebar read as an app banner at p = 0.99.
- The floor of 0.6 comes from 57 records. It looks like a clean gap (correct annoyance labels ≥ 0.82, most mistakes ≤ 0.56), but it should be rechecked on a larger set.
- The prompt still asks for a `target` button. Nothing reads it any more, but deleting that line from the prompt cost 5 points in generate mode (84% → 79%) and 5 in scoring mode (82% → 77%), so it stays.
- The confidence numbers are only partly calibrated: the 0.9–1.0 group is right 93% of the time, and the misses there are site headers at p = 1.00, which the header guard already handles.

## Measure and tune (step 4, 2026-10-25)

Same 30 sites and setup as before (cloud container in Ohio, US address; Chromium with Pixel 7 emulation; Qwen3-0.6B on 4 x86 cores). Reports: [before the change](results/2026-10-25/crawl-1-before.md), [after](results/2026-10-25/crawl-2-after.md), [with consent tools' scripts allowed](results/2026-10-25/crawl-3-consent-tools-allowed.md).

### How long overlays stay on screen

The step 2 number, "the first action came 1.6–4.6 s after navigation", measured from navigation start, and most of that is the page loading before the banner exists at all. What matters to the reader is how long an overlay is visible. The harness now has an exposure probe ([`browser.mjs`](../engine/harness/browser.mjs), harness only): every 100 ms it samples the detector's 18 viewport points and records, per fixed/sticky overlay, how long it was actually visible (display, visibility and opacity of the element and its ancestors). Crawl reports have an *Exposure* section, and fixture tests can set `maxVisibleMs`. List-hidden and cached overlays never show, so they never appear there.

| | Before | After |
|---|---|---|
| Overlays the engine acted on (rules and serene modes, first visits) | 7 | 5 |
| Visible for, each (ms) | 0, 0, 0, 0, 96, 173, 1440 | 0, 0, 89, 91, 250 |
| Worst case | Corriere's consent wall, 1.4 s (rules mode) | weather.com's "Get the app" pill, 250 ms |
| Detector scans per page: median / max | 9 / 28 | 17 / 102 |
| Detector time per page: median / max | not recorded | 70 ms / 721 ms (Reddit, 49 scans) |

- Most overlays were already gone within 200 ms: anything with a heuristic score of 0.6 or more is quarantined (hidden while the host decides) the moment the detector sees it. The long tail came from scans that ran late.
- **Throttled, not debounced.** A scan used to run 250 ms after the *last* DOM change. A page that keeps changing while it loads (ads, tickers, lazy images) pushed it back for as long as it kept changing, and Corriere's wall waited for a scheduled scan. Now a scan runs at most 100 ms after the *first* change. New class/id tokens go to the host first, and that scan waits up to 300 ms for the generic rules, so list-covered banners still never reach the detector. The gap between scans grows to 10× the cost of the last one, which caps the detector at about 10% of the main thread on a slow phone.
- **Fade-ins.** A popup that fades in is not visible yet when its DOM change triggers a scan, and when the fade ends there is no DOM change. Scans now also run on `transitionend`/`animationend`, at `load` and 300 ms after DOMContentLoaded, and again 200 ms later (up to 10 times) while an overlay is on top but not visible yet. The fixture [`newsletter-fade-busy`](../engine/test/fixtures/newsletter-fade-busy.html) (a page that changes every 80 ms plus a fading signup modal) failed with 900 ms on screen before this change and passes under its 600 ms limit after.
- The cost is about twice as many scans. A scan costs 5–30 ms on a desktop core on big pages (Spiegel, the Guardian), so 721 ms over a 16 s visit is about 4% of one core. Phone numbers are the open question; the throttle bounds it.
- No new false positives. The extra scans found one more overlay, iDNES's fixed ad slot (36% of the screen): the heuristics and the model called it content and left it alone, as they should (ads are the lists' job).

### Consent tools' scripts

On 2026-10-24, autoconsent rarely saw a banner, because the lists block consent tools' scripts (OneTrust's `cookielaw.org`, for example) at the network level. No consent is given that way, but no "reject" is recorded either, and US sites treat no answer as consent. With autoconsent on, the engine now lets the consent tools' own hosts through the lists (`CONSENT_TOOL_HOSTS` in [`autoconsent.mjs`](../engine/src/host/autoconsent.mjs), `ConsentTools` in the app). These are tools autoconsent has rules for. Their banners are list-matched, so they stay parked (invisible) while autoconsent answers them.

| Site | Lists block the consent tool | Consent tool allowed |
|---|---|---|
| Stack Overflow | no banner, nothing recorded | OneTrust: opted out |
| Gymshark | no banner, nothing recorded | OneTrust: opted out |
| the Guardian, Spiegel, nu.nl | opted out (Sourcepoint, DPG Media) | same |
| everything else, incl. the 3 controls | | no change |

Nothing was visible meanwhile (no exposure on either site), and there were no new false positives. The cost is one request per site to the consent tool's server. `npm run crawl -- --block-consent-tools` restores the old behaviour for comparison. This is from a US address: GDPR banners from an EU address are the next measurement.

**From the EU (Artur's phone, Estonia, 2026-10-25).** Spiegel: Sourcepoint answered, no banner seen. Stack Overflow: OneTrust opted out, but its banner flashed briefly. Stack Overflow puts the banner in the page's HTML for EU visitors, and autoconsent's prehide (the consent tools' containers at opacity 0 while it works) only starts with the host's init reply. On a phone the page can paint before that reply. A test that holds the host's messages for 800 ms showed a banner like that on screen for 700 ms. Now `dist/autoconsent.js` hides the same containers from document start in the top frame (438 selectors that `build.mjs` takes from autoconsent's generic rules) and lets go when autoconsent starts, when the init reply has no autoconsent (site turned off), or after 2 s. The same test now sees nothing ([`cookie-in-html`](../engine/test/fixtures/cookie-in-html.html)). Delfi: CookieYes was found; postimees.ee had nothing to act on.

### The model on the phone

- **No confidence from LiteRT-LM yet.** The plan was a JNI shim over LiteRT-LM's C API (`litert_lm_session_run_text_scoring`). The 0.18.1 AAR's `liblitertlm_jni.so` exports only the Kotlin API's own 52 JNI functions, though, and none of the C API. A shim would mean building LiteRT-LM from source for each ABI. The Kotlin `Session` has `runPrefill`/`runDecode`, but no logits. The C API in the desktop `litert-lm` 0.18.1 package does score text, but only one target per prefill gives sane numbers, so a probability over nine labels would cost nine prefills. So on the phone the model answers without a probability, and `Policy.fromModel` acts on its label.
- **First phone run (Galaxy Z Fold 7, Snapdragon 8 Elite, GPU), 2026-10-25.** The model loaded in 3.2 s. A call took 3.1 s (median) with the constrained JSON reply, and 2.8 s when cut off after the label, with the first chunk at 2.6 s. So nearly all of it is prefill: about 240 prompt tokens (204 of them the system prompt) at roughly 100 tokens/s. Accuracy was 67% (75% with the header guard), against 84% for the same model under llama.cpp. 15 of the 19 misses were `app_install` or `notification_prompt`.
- **The accuracy drop is LiteRT-LM's JSON constraint, not the phone.** The same `.litertlm` file through the desktop `litert-lm` 0.18.1 build (CPU) gives the same 70% with the JSON constraint on. Its grammar forces the compact `{"label":"` form, which this model never writes on its own (it writes `{"label": "`), and that pushes it towards the wrong labels. With no constraint, the reply streamed and cut off once the label string closes, and a label outside the nine counted as content, it is 89% (51/57), and 95% with the header guard. All 57 replies had a label. The app now uses that (`LiteRtClassifier.Reply.FREE`; `Reply.JSON` is the old way). The engine's own `target` was already ignored, so nothing else changes.
- **Second phone run, free reply (build 36, same phone), 2026-10-25.**

  | Backend | Load | Median / p95 per call | First chunk | Accuracy | With the header guard |
  |---|---|---|---|---|---|
  | GPU, constrained JSON (first run) | 3.2 s | 3065 / 3330 ms | 2561 ms | 67% | 75% |
  | **GPU, free reply** | 3.6 s | **1135 / 1510 ms** | 775 ms | 74% | 81% |
  | CPU, free reply | 1.4 s | 4106 / 5051 ms | 3379 ms | 82% | 88% |

  Dropping the JSON constraint made the GPU almost 3× faster: most of the 3 s was LiteRT-LM setting up the constraint for each call, not prefill. The CPU comes close to the desktop LiteRT-LM number (89%, 95% with the guard), and the GPU is 7 points lower, presumably from its lower-precision arithmetic. Most of the GPU's extra misses are cookie banners called content (Ketch, OneTrust, Sourcepoint's `pmConsentWall`, CNN's dialog, two synthetic ones). That is the safe way to be wrong. The lists and autoconsent handle those banners before the model sees them, so on a real page they rarely reach it. The misses that could hide real content are the same count on both backends once the header guard runs: Medium's bar and Healthline's sticky nav on the GPU, Medium's bar and the `neg-article-mentions` fixture on the CPU. So the app stays GPU first, CPU as the fallback.
- **The system prompt, read ahead of time.** About 0.8 s to the first chunk on the GPU was mostly prefill of the 204-token system prompt, redone on every call because the app starts a new conversation for each one. LiteRT-LM 0.18.1's Kotlin API cannot copy or rewind a session's KV cache, but a conversation can prefill its system prompt when it is created (`prefillPrefaceOnInit`). So `LiteRtClassifier` keeps the next conversation ready: after each call (and when the model loads) it builds one in the background, and the next call only prefills the overlay's own ~35 tokens (`LiteRtClassifier.prepare`). The same split on the desktop LiteRT-LM build (CPU, sessions, same tokens): 51/57 right either way, and the per-call median went from 2374 ms to 1300 ms, with the 966 ms of system prompt done in advance.

  | Galaxy Z Fold 7, build 44 | Per call (median / p95) | First chunk | Preparing | Accuracy | With the header guard |
  |---|---|---|---|---|---|
  | GPU, new conversation per call | 1095 / 1372 ms | 775 ms | – | 74% | 81% |
  | GPU, prepared | 825 / 1102 ms | 517 ms | 246 ms | 70% | 77% |
  | CPU, prepared | 2404 / 4323 ms | 1541 ms | 2535 ms | 82% | 88% |

  On the CPU the answers are exactly those of the CPU without preparing (build 36), and a call is 41% faster. On the GPU, prefilling the system prompt separately changed three of 57 answers: Corriere's cookie wall and DuckDuckGo's app banner became content, which is only a miss, and the `neg-article-mentions` fixture became an app banner, which would hide real content. The GPU's lower-precision arithmetic gives slightly different numbers when the prompt is prefilled in two parts, and some of these answers are close calls. So preparing was on for the CPU only at first, until a bigger eval set could show whether the GPU difference is noise. On 159 records it is (see the next point): the GPU hid the same 5 of 80 content records with and without preparing, so preparing is now on for both. Back-to-back calls still wait for the preparation, so it helps most when a page has one uncertain overlay, which is the usual case. The heuristics and the per-site cache decide most overlays anyway, and every verdict the model gives is cached.
- **A bigger eval set, and INT8 against INT4.** The 57 records left too few chances to tell two models or two backends apart: on the phone, 81% against 86% was three answers. The set now has 159: 41 more overlays from a crawl of 45 sites (Estonian, Finnish, Latvian, Lithuanian, Nordic, Polish, Czech, French, German, Italian and Spanish news, plus shops and US sites), one Dutch site header from the earlier crawl, and 60 hand-written records. Most of the new ones are things the model must leave alone (site headers, bottom tab bars, carts, share sheets, article text about cookies), so 80 of the 159 are content. The rest are annoyances, many of them in the languages above. On the desktop LiteRT-LM build (CPU, free reply, new conversation per call):

  | | INT8 (586 MB) | INT4 (345 MB) |
  |---|---|---|
  | Label accuracy | 80% (127/159) | 73% (116/159) |
  | With the header guard | 83% | 77% |
  | Content hidden (guard included) | 6 of 80 | 10 of 80 |
  | Median per call | 2313 ms | 1840 ms |

  INT4 often wraps its answer in a Markdown code fence, and its misses lean towards `app_install` for carts, checkout steps and headers, which is the costly kind. On the 57-record set the phone told another story on the GPU (INT4 785 ms and 86% with the guard, against INT8's 1095 ms and 81%), so the phone runs on the 159 records decide. They agree with the desktop (Galaxy Z Fold 7, build 52; with the header guard; content hidden of 80):

  | | INT8 | INT4 |
  |---|---|---|
  | GPU | 1099 ms, 74%, 5 hidden | 752 ms, 75%, 12 hidden |
  | GPU, prepared | 964 ms, 73%, 5 hidden | 649 ms, 75%, 12 hidden |
  | CPU, prepared | 2505 ms, 81%, 7 hidden | 1195 ms, 72%, 12 hidden |

  INT4 is about a third faster, but it hides real content more than twice as often, so INT8 stays the default and the INT4 entry in Settings says so. On INT8 the GPU is less accurate than the CPU, but most of its extra misses are cookie banners called content, which leaves the banner up (and the lists and autoconsent remove most of those before the model sees them), and it hides less content. So the GPU stays first. Neither model reads Estonian, Swedish or Latvian cookie banners well yet: those come out as content (a miss, not a false positive). Logs: [INT8](results/2026-10-25/litert-int8-159.txt), [INT4](results/2026-10-25/litert-int4-159.txt).
- **Bigger models, and other 4-bit builds.** The likeliest small text models on [litert-community](https://huggingface.co/litert-community) with a generic `.litertlm` build of about 1 GB or less, plus Qwen3-1.7B INT8 as a ceiling, went through [`test/litert-eval.py`](../engine/test/litert-eval.py) (desktop LiteRT-LM 0.18.1, CPU, the app's prompt and free reply, a new conversation per call; content hidden is with the header guard, of 80):

  | Model (file) | Size | Guarded | Content hidden | Median per call |
  |---|---|---|---|---|
  | Qwen3-0.6B INT8 (the default) | 614 MB | 83% | 6 | 1835 ms |
  | Gemma 3 1B INT4 (`gemma3-1b-it-int4`) | 584 MB | 77% | **2** | **840 ms** |
  | Qwen3-1.7B INT8 | 2.06 GB | **87%** | 10 | 2128 ms |
  | Qwen3-1.7B INT4 (`dynamic_wi4b32`) | 977 MB | 74% | 24 | 3938 ms |
  | Qwen3-0.6B INT4, TorchAO mixed (`qwen3_0_6b_mixed_int4`) | 498 MB | 84% | 10 | 1791 ms |
  | Qwen3-0.6B INT4 (`qwen3_0.6b_nothink_q4_block32`) | 347 MB | 69% | 13 | 1068 ms |
  | LFM2.5-1.2B INT4 | 736 MB | 56% | 1 | 1389 ms |
  | Qwen3.5-0.8B INT8 | 965 MB | 50% | 1 | 1700 ms |

  More parameters at 4 bits do not help Qwen: the 1.7B INT4 build hides real content four times as often as the 0.6B INT8 one, and the INT8 1.7B is the most accurate but still hides 10. Gemma 3 1B is the only model that hides less content than the default, and it is twice as fast on the CPU. Its misses are mostly the safe kind: it often writes a phrase from the overlay instead of a label (`"label": "Open In App"`, `"Subscribe"`), which counts as content and leaves the overlay up, so app banners, paywalls and login walls suffer most. The same with or without the trailing `/no_think` (77%, 2 hidden). LFM2.5 copies the prompt into the label most of the time. Qwen3.5-0.8B (a hybrid model with recurrent state) loops on `<think>` blocks after the first call, because a new LiteRT-LM conversation on the same engine keeps the last one's state ([LiteRT-LM#3165](https://github.com/google-ai-edge/LiteRT-LM/issues/3165)); with a new engine per call it gets 85% of the first 40 records, but a 24 s load per call rules that out. Scored as GGUF on llama.cpp instead (Unsloth builds, `test/llm-eval.mjs` single-pass scoring, where Qwen3-0.6B Q8_0 gets 83% and hides 12), Qwen3.5-0.8B is worse than Qwen3-0.6B at either width: Q8_0 75% and 20 hidden, Q4_K_M 64% and 32 hidden (Qwen3-0.6B Q4_K_M: 70%, 30), so the model itself is no better, apart from the runtime bug. The TorchAO INT4 build of Qwen3-0.6B needs its own chat template (its built-in one fails on LiteRT-LM 0.18.1) and hides 10. Llama 3.2 1B and Gemma 3 1B are gated on Hugging Face; the Gemma file was scored from a public copy with the same name and byte size. So Gemma 3 1B INT4 went into Settings for a phone run (it has since been taken out, see below). On the phone (Galaxy Z Fold 7, build 70; with the header guard; content hidden of 80; Qwen's numbers from build 52):

  | | Qwen3-0.6B INT8 | Gemma 3 1B INT4 |
  |---|---|---|
  | GPU | 1099 ms, 74%, 5 hidden | 952 ms, 75%, 2 hidden |
  | GPU, prepared | 964 ms, 73%, 5 hidden | 1029 ms, 75%, 2 hidden |
  | CPU, prepared | 2505 ms, 81%, 7 hidden | 2439 ms, 75%, 4 hidden |
  | Load | about 3 s | 6.2 s (GPU) |

  Memory the model adds to the app (build 85, PSS, largest sample between calls): Gemma +2351 MB on the GPU (1912 MB of it graphics) and +1133 MB on the CPU; Qwen +2450 MB on the GPU (2285 MB graphics) and +1654 MB on the CPU; Qwen INT4 +1387 MB on the GPU and +801 MB on the CPU, with GPU calls of 785 ms (658 ms prepared) but 12 of 80 content items hidden. The GPU holds both models' weights unpacked, so the smaller INT4 file saves little there. In the same build, run one after the other, Qwen's GPU calls took 1541 ms (1375 ms prepared) against Gemma's 985 ms, probably partly because the phone was warm by then.

  On the GPU, Gemma is as fast and as accurate as Qwen and hides less than half as much content: only `neg-privacy-article` and `neg-language-picker`, both called cookie banners. Preparing the next conversation does nothing for Gemma (5 ms), because its chat template has no system turn: the instructions go into the user turn, so there is nothing to prefill ahead of the overlay. On the phone its wrong labels lean towards `cookie_consent` where the desktop build wrote a phrase from the overlay, and app banners, paywalls, surveys and chat widgets still mostly come out as content (left up). Logs: [results/2026-10-25/larger-models](results/2026-10-25/larger-models/).

  Gemma's lower count of hidden content comes mostly from recognising less. Of the 40 annoyances that are not cookie banners (desktop), Gemma names 9 correctly and Qwen3-0.6B INT8 26: app banners 3 of 12 against 10, newsletters 3 of 8 against 6, push prompts 0 of 5 against 4, login walls 0 of 4 against 3. The rest Gemma calls content or a cookie banner. Prompt changes don't fix that. Tuned on every other record (`litert-eval.py --variant NAME --half tune`, variants in [`test/prompt-variants.mjs`](../engine/test/prompt-variants.mjs)) and checked on the rest (`--half check`):

  | Prompt (Gemma 3 1B INT4, desktop) | Tune half: guarded, hidden of 41 | Check half: guarded, hidden of 39 |
  |---|---|---|
  | The app's (base) | 76%, 1 | 77%, 1 |
  | Without the unused `target` line | 74%, 0 | 72%, 0 |
  | ...plus "the label must be one of: ..." in the instructions | 59%, 15 | |
  | ...the same at the end of the overlay message | 42%, 24 | |
  | ...cookie_consent listed last / content listed first | 62%, 0 / 62%, 0 | |
  | ...ending with "Which label fits this overlay?" | 59%, 17 | |
  | ...instructions as an earlier turn answered "OK" (preparable) | 65%, 0 | |
  | ...3 / 6 worked examples as earlier turns | 65%, 1 / 59%, 0 | |

  Gemma 3 1B leans to whichever label it reads first or last: naming the nine labels again turns most answers into `cookie_consent`, and moving that label turns them into `newsletter`. Worked examples and the preparable layout push it towards content. Logs: [results/2026-10-25/larger-models/gemma-prompts](results/2026-10-25/larger-models/gemma-prompts/).

  Decided 2026-10-26: one model to tune, Qwen3-0.6B INT8, which stays the default. Qwen3-0.6B INT4 stays in Settings as the lighter choice. Gemma 3 1B is out of the app: it catches a third as many of the non-cookie annoyances, and its file is gated.

  The same prompt variants on Qwen3-0.6B INT8 (desktop, guarded, hidden): the app's prompt 81%, 3 of 41 on the tune half and 85%, 3 of 39 on the check half. Without the `target` line it looked as good on the tune half (80%, 0 hidden) but fell to 77%, 6 hidden on the check half, so the prompt stays. Worked examples cost Qwen 13 to 17 points, and naming the labels again 7. Sampling settings have nothing left to give: decoding is greedy and stops when the label closes. Logs: [results/2026-10-25/larger-models/qwen-prompts](results/2026-10-25/larger-models/qwen-prompts/).

  The header guard now also covers thin nav bars at the bottom (a `<nav>`, `role=navigation`, or nav/tab-bar/menu class names; mobile tab bars) and top bars with six or more links. That catches two of Qwen's six hidden items, Booking's bottom nav (called a login wall) and Le Figaro's header (called a push prompt), and matches none of the 79 annoyances in the labelled set. Desktop Qwen INT8 goes from 83% guarded and 6 hidden to 84% and 4. The other four, two carts called app banners, a weather alert and an article about cookie rules, have no shape a rule could tell apart from real annoyances (a price check would also match three paywalls and a cookie wall).
- **On-device benchmark.** Settings → On-device model → *Run benchmark* runs the 159 labelled records from [`labeled-features.jsonl`](../engine/test/data/labeled-features.jsonl) (packaged as an asset) through the model with the free reply: on the GPU with a new conversation per call, on the GPU with prepared conversations, and on a CPU-only engine with prepared conversations. It reports the phone and SoC, the load time, the median and p95 latency, the time to the first chunk, the label accuracy with and without the header guard, and how many of the content records the engine would hide (a false positive, header guard included). *Copy results* puts the report on the clipboard.
- **`target` in the output.** The model's button choice stays in the prompt, because removing that line cost 5 points of accuracy (see above). Cutting the reply off after the label gets the speed without changing the prompt.
- **The NPU (experimental).** LiteRT-LM 0.18.1 has `Backend.NPU`, and on a Qualcomm phone it needs two sets of libraries in the app: LiteRT's Qualcomm dispatch and compiler plugin (`com.google.ai.edge.litert:litert-npu-runtime-qualcomm`) and Qualcomm's QNN runtime (`com.qualcomm.qti:qnn-runtime`, the QAIRT 2.50 release that LiteRT-LM's LiteRT is built against). The app packages only the HTP backend, its on-device compiler (`libQnnHtpPrepare.so`) and the V79 halves for the Snapdragon 8 Elite (SM8750). There is no Qwen3-0.6B build compiled ahead of time for a Snapdragon (litert-community has one for MediaTek's MT6993 only, and Qualcomm builds of Gemma 3, whose prompts longer than 128 tokens lose everything after the first chunk on Qualcomm NPUs: LiteRT-LM issue 3508). So LiteRT-LM takes its generic path with the plain file: the compiler plugin compiles what the HTP supports when the model loads, and the rest runs on the CPU. On a Qualcomm phone, Settings → On-device model → *Run NPU benchmark* runs the same records that way, in a separate `:npu` process so that an abort in the vendor libraries cannot take the browser down. Its report has the load time (with the compile), the per-call latency and accuracy, the NPU libraries it found, and LiteRT's and QNN's log lines about the partition. If the process dies, the report says so and carries its last log lines. A per-call time close to the CPU's means little of the graph ran on the NPU. On a Galaxy Z Fold 7 the plain INT4 model took 7.9 s a call this way (the GPU takes 0.8 s), so most of it stayed on the CPU.
  The ahead-of-time build is `android/npu-model/build.py`, which the `npu-model` workflow runs on a GitHub runner (installing the Qualcomm SDK package downloads QAIRT, which the cloud sandbox can't). It follows litert-torch's NPU pipeline with its Qwen3-0.6B / SM8750 config: export, calibration on 102 of our labelled prompts that the benchmark doesn't measure (`calibration.jsonl`, from `calibration.mjs`), a16w8 static quantization, and the Qualcomm compile, with a 512-token KV cache. The workflow uploads the 781 MB `.litertlm` as an artifact; *Pick NPU model* in Settings imports the artifact's zip, and the NPU benchmark then runs it. The static quantization costs accuracy: on the desktop CPU the a16w8 model got 21 of the 57 benchmark records right, the int8-weight export 40. Three things had to be right for it to run on the phone: the compile uses the ai-edge-litert release that matches the app's dispatch library (2.3.0, not the nightly); the prefill signature's last-layer KV inputs, which prefill never reads, are given decode's int16 type before the compile, because LiteRT-LM shares one buffer between the two signatures ("Custom allocation is too small"); and the app passes a ChatML template (`NpuBenchmark.CHATML`), because the Hugging Face template the export carries concatenates message contents as strings, which LiteRT-LM passes as lists. On the Fold 7 (build 157, npu-model-9, 159 records) it answers in 369 ms a call (p95 561 ms, first token 149 ms, load 0.8 s), against 1099 ms for INT8 on the GPU, with 72% accuracy with the header guard (GPU INT8 74%) and content hidden 3 times of 80 (GPU INT8 5). It holds about 0.7 GB with the model loaded (PSS +698 MB; the phone's available memory drops by up to 1 GB, which also counts the HTP's shared buffers), against 2.4 GB for INT8 on the GPU and 1.4 GB for INT4. The desktop CPU's low score for the quantized model did not carry over to the HTP. Most misses are cookie banners called content; the harmful ones are site headers called login_wall and cart widgets called app_install. So on an SM8750 phone the model list offers *Qwen3-0.6B (NPU)* and picks it by default: the `npu-model-release` workflow publishes a tested npu-model artifact as a release of this repo, and the app downloads and checks it like the other models. It runs in the browser's process, as the other models do. The app no longer packages the HTP's on-device compiler (`libQnnHtpPrepare.so`, 85 MB), so models not compiled ahead of time don't run on the NPU. Each further chip needs a build for it (litert-torch has configs for the Snapdragon 8 Gen 2, 8 Gen 3 and 8 Elite Gen 5, Hexagon V73, V75 and V81), its Stub and Skel libraries kept in the APK (about 20 MB uncompressed) and a ModelCatalog entry; on a Snapdragon without one, Settings links to the *NPU model request* issue form with the phone filled in. The model is 780 MB: 610 MB of transformer with its int8 output layer, which shares Qwen's 156 MB embedding table, plus that table again in int8 for the CPU-side embedder. Halving the embedder to int4 would save about 80 MB.

## Running

```sh
cd engine
npm install                 # builds adblock-rust (needs Rust); Node ≥ 22
npx playwright install chromium
npm run build               # dist/autoconsent.js (npm test runs it first)
npm test                    # fixtures + unit tests (about 1 minute)
npm run model               # Qwen3-0.6B Q8_0 GGUF, ~640 MB, from huggingface.co
npm run llm-eval            # model accuracy + latency on test/data/labeled-features.jsonl
npm run llm-eval -- --mode generate   # the older grammar-constrained JSON mode, for comparison
npm run crawl               # 30 real sites → results/<time>/report.md + shots/
npm run crawl -- --only bbc,spiegel --headed
npm run crawl -- --no-autoconsent
npm run crawl -- --block-consent-tools   # the lists block consent tools' scripts, as before 2026-10-25
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
| `dist/autoconsent.js` + `serene.js` | one document-start script, autoconsent first (`Tab.contentScript`). `npm run build` also writes `dist/autoconsent-rules.json` (autoconsent's compact rules, 16.61.0) and `dist/autoconsent-config.json`; the app packages `dist/` as assets and [`AutoconsentRules.kt`](../android/app/src/main/kotlin/app/serene/engine/AutoconsentRules.kt) ports `filterCompactRules`. The rules are a snapshot from the build: refreshing them at runtime like the filter lists is a later step |
| `RulesEngine` consent engine | `RulesEngine.consent`: a second adblock-rust engine from `FilterLists.consentPart`, saved next to the main one as `consent.dat` |
| `LlamaClassifier` single-pass scoring (llama.cpp logits) | not ported yet. LiteRT-LM's C++ and C APIs have text scoring: `Session::RunTextScoring` / `litert_lm_session_run_text_scoring`, after `RunPrefill`, scores a list of candidate texts in one batched pass and returns their log-likelihoods. Score the nine label strings after the prompt and `{"label": "`. The Kotlin API (0.18.1) does not expose it, and the AAR's JNI library has no C API to call (see [The model on the phone](#the-model-on-the-phone)). The app lets the model write freely and stops after the label, which gives no confidence, so `Policy.fromModel` acts on the label as before |

## Totals on the start page (2026-10-26)

The app's new tab page shows lifetime totals ([`Stats.kt`](../android/app/src/main/kotlin/app/serene/stats/Stats.kt), saved to `stats.json`; the engine reports through `EngineEvents`):

- *Annoyances cleared*, by label: candidates the engine clicked away or hid (cached rules too), cookie banners autoconsent opted out of, and cookie banners the lists hid (`listHit`). An element counts once per page, and a page has at most one cookie banner however many layers dealt with it. Other elements the lists' cosmetic rules hide are not counted: nothing tells a real pop-up from a hidden empty box.
- *Requests blocked* by the lists, by request type. Few: these are annoyance lists, not ad or tracker lists; on the 30 crawl sites (from the US, 2026-10-26) they would block 27 requests, 25 of them cookie, newsletter and chat widget scripts. Data and time saved were tried and dropped (Artur, 2026-10-26): both could only be estimates.
- Tracking refused (autoconsent opt-outs), pages visited with Serene on, model calls and their average time, and the three sites with the most blocked requests and annoyances (at most 200 sites are kept, the busiest; Clear browsing data forgets them).

## Known gaps

- **Unmeasured:** on-device latency and battery (the in-app benchmark covers latency), the detector's main-thread cost on a phone, and the crawl from an EU address. The real-site numbers above come from 29 sites and one US location. That is enough to find bugs, but too few to tune thresholds on.
- Decline buttons are matched by a word list in about 20 languages, now including Japanese, Chinese, Korean, Russian, Turkish and the Nordic languages. Consent tools that autoconsent knows are answered in any language. For anything else, a decline button in another language cannot be clicked, so the overlay is hidden instead. That is the safe fallback, but it leaves the consent undecided.
- Consent tools autoconsent does not know, or whose hosts are not in `CONSENT_TOOL_HOSTS`, stay blocked by the lists, so nothing is recorded for them.
- Autoconsent rules can be wrong for a region (the Healthline rule clicked a menu button from a US address). Disabled rules go in `AUTOCONSENT_CONFIG.disabledCmps`; an EU crawl would show more.
- Autoconsent adds about 160 KB of rules to each top-frame init reply in the prototype (see the porting table for how the app avoids that).
- An overlay that goes to the model and scores under 0.6 on the heuristics stays visible while the model decides (not quarantined). That was 4 calls in 58 loads, but each takes as long as the model does on the phone.
- The crawl's "annoyances" count comes from the detector's own heuristics. It over-counts headers that contain "Log in Sign up" (Pinterest), so check the screenshots.
- Scriptlets are applied after the host replies (async), not strictly before page scripts. That matches what WebView allows without rewriting HTML responses. Some uBO scriptlets lose races because of it.
- Unsupported procedural operators (`xpath`, `matches-path`, `matches-attr`, `:matches-css-before/after`) are skipped.
- Closed shadow roots are opaque: the detector sees only the host element.
- Inline paywalls and in-article newsletter boxes are out of scope on purpose. Only fixed or sticky overlays are handled, plus whatever the lists hide.
- The heuristic thresholds (0.85 act, 0.25 ignore, 0.2 structure → model) held up on the crawl, but 29 sites is too few to tune them.
