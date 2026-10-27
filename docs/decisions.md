# Serene: stack and model decisions

Status: accepted (model choice: Qwen3-0.6B) · 2026-10-24 · Step 1 of 4 (research). Steps 2–4 (engine prototype, MVP shell, measure & tune) build on this.

## Summary

| Area | Decision |
|---|---|
| App | Kotlin, Jetpack Compose shell, system WebView via `androidx.webkit` |
| Script injection | `WebViewCompat.addDocumentStartJavaScript` (all frames, before page scripts); fallback `evaluateJavascript` in `onPageStarted` on old WebViews |
| JS ↔ native bridge | `WebViewCompat.addWebMessageListener`, not `addJavascriptInterface` |
| Rules engine | Brave's `adblock-rust` (MPL-2.0) built for Android via `cargo-ndk`, called over JNI |
| Filter lists | EasyList Cookie List, Fanboy's Annoyances, uBlock filters – Annoyances / Cookie Notices; downloaded and updated at runtime, not bundled |
| Model (default) | **Qwen3-0.6B** (Apache-2.0), `.litertlm` (~0.6 GB), prompted zero-shot with thinking disabled; GPU backend preferred |
| Model (fallback) | Qwen2.5-0.5B (~0.5 GB, Apache-2.0) on devices without a usable GPU backend; fine-tuning either model is an option in step 4, not a requirement |
| Runtime | **LiteRT-LM** Kotlin API (stable; CPU, GPU, NPU on Android) |
| Not chosen | MediaPipe LLM Inference (now maintenance-only, superseded by LiteRT-LM); llama.cpp kept as a fallback runtime only |
| Model delivery | Downloaded on first run (or Play Asset Delivery), never inside the APK |
| Rejected | Gemma 3 270M: fastest, but needs fine-tuning to classify well, and the Gemma terms carry a use policy |

## 1. App stack

**Kotlin + system WebView.** The system WebView is Chromium, updated through the Play Store, so we get a modern engine at ~0 APK cost. A Chromium fork (or GeckoView, ~50–80 MB) would give extension-grade hooks but is not needed for overlay removal. Target minSdk 26; every injection API below is feature-checked with `WebViewFeature.isFeatureSupported`.

**Hooks we rely on:**

- `addDocumentStartJavaScript(webView, script, allowedOriginRules)` (`DOCUMENT_START_SCRIPT`): our content script runs at document start in the main frame and matching iframes, before page scripts. This is the closest WebView gets to an extension content script. It injects the cosmetic CSS, scriptlets, and the overlay detector.
- `addWebMessageListener` (`WEB_MESSAGE_LISTENER`): origin-scoped message channel with an `isMainFrame` flag and a reply proxy. Safer than `addJavascriptInterface`, which exposes a Java object to every frame.
- `WebViewClient.shouldInterceptRequest`: network blocking (return an empty `WebResourceResponse`) for known consent-management and annoyance scripts. Limits: no request bodies, runs on a background thread, and service-worker requests need `ServiceWorkerControllerCompat`. Good enough for blocking script/frame URLs.
- `WebChromeClient.onPermissionRequest` / no Notifications API: WebView does not support web push, so *native* notification prompts are not a problem. What users see are *soft* pre-prompts ("Get notified?") built in the DOM, and the detector handles those like any other modal.

## 2. Filter lists and the rules engine

`adblock-rust` parses ABP and uBO syntax, does network matching, cosmetic filtering (generic + hostname-specific hide selectors), and with the `resource-assembler` feature supports uBO scriptlets (e.g. `set-cookie`, `remove-class`, `trusted-click-element`). It is what Brave ships on Android, so performance on phones is proven. Per-ABI native size is a few MB.

Flow per navigation:

1. Network request → `engine.check_network_request` → block or allow.
2. Page start → `engine.url_cosmetic_resources(url)` → hide selectors + scriptlets injected by the document-start script.
3. After DOM settles → page sends class/id sets → `engine.hidden_class_id_selectors` → more CSS (generic rules).

Lists: EasyList Cookie List and Fanboy's Annoyances (GPLv3 / CC BY-SA), uBlock filters – Annoyances and Cookie Notices (GPLv3). Fetching them at runtime from their upstream URLs keeps list licensing separate from app code and keeps rules fresh (refresh every 1–4 days, over Wi-Fi / unmetered by default). Ship a small compiled snapshot only if first-run-offline matters. uBO's scriptlets (GPLv3) are the exception: there is no upstream file adblock-rust can read, so `npm run build` turns a pinned uBO release into `engine/dist/scriptlets.json`, which the app ships as an asset; the app is GPL-3.0 too.

Rules alone catch most cookie banners on big sites. The LLM is for the long tail: small sites, newsletter and "special offer" modals, custom login walls, and new CMP variants the lists haven't caught yet.

## 3. Hybrid engine (rules first, LLM for the long tail)

```
page ──► network rules ──► cosmetic rules + scriptlets ──► overlay detector (JS)
                                                               │ candidate
                                                    site cache hit? ──yes──► apply cached action
                                                               │ no
                                                    heuristic score ─high/low─► act / ignore
                                                               │ uncertain
                                                         LLM classifier (native)
                                                               │ label + action
                                                    apply, then cache as a local rule
```

**Detector (JS, cheap).** A `MutationObserver` plus a scan after load flags elements that are `position: fixed|sticky`, have a high z-index, cover a large share of the viewport or sit at the bottom edge, or coincide with `overflow: hidden` on `<html>/<body>`. It scores them with keyword and structure heuristics (consent, cookie, subscribe, notifications, "allow", email input, close button, iframe from a known CMP domain).

**Features sent to the model** (~150–300 tokens): the visible text (trimmed), button and link labels with indexes, `role`/`aria-*`, coarse geometry (viewport coverage, edge), tag, id/class tokens, and the iframe origin. No full HTML. Everything stays on the device.

**Model task.** Classification, not generation. Output is constrained JSON of ~10 tokens:

```json
{"label": "cookie_consent", "action": "click", "target": 2}
```

Labels: `cookie_consent`, `newsletter`, `notification_prompt`, `paywall_teaser`, `app_install`, `login_wall`, `chat_widget`, `survey`, `content` (leave alone). Actions: `click` (button index, e.g. "Reject all" / "Close"), `hide`, `ignore`.

**Acting.**
- Cookie banners: prefer clicking reject or "necessary only" over hiding, because hiding can leave the scroll locked and the consent undecided. Fall back to hiding, then restore scrolling.
- Other modals: hide the element and its backdrop, restore `overflow`, unblur siblings.
- Paywall teasers: remove the nag overlay only. We do not try to unlock paid content. That's a product and legal line, not a technical one.
- Every action has a per-site "show what was blocked / undo" control. Users who undo something become a source of labeled data, if they opt in.

**Caching.** The decision becomes a local cosmetic rule keyed by `(eTLD+1, stable selector signature)`. After the first visit a site is handled by rules only, so the model typically runs about once per new site, not once per page.

**Avoiding the flash.** Overlays usually appear after first paint. When the heuristic score is high, quarantine the candidate (`visibility: hidden`) while the model runs. If it says `content`, restore it.

## 4. Model and runtime

**Runtime: LiteRT-LM.** Google's on-device LLM runtime. The Kotlin API is stable and supports CPU, GPU and NPU backends on Android, plus constrained decoding. MediaPipe LLM Inference is now in maintenance-only mode with a pointer to LiteRT-LM, so we start on LiteRT-LM. llama.cpp (GGUF via JNI) is the fallback if we need a model LiteRT-LM can't run. It has a wider model choice but needs our own NDK build, has no NPU path, and is CPU-bound on most phones.

**Candidates** (vendor figures from the LiteRT-LM model table; we measure on our own devices in step 4):

| Model | Download | CPU prefill / decode tok/s | GPU prefill / decode | Licence | Fit |
|---|---|---|---|---|---|
| Qwen3-0.6B | ~0.59 GB | 165 / 9 (Vivo X300 Pro) | 580 / 21 | Apache-2.0 | **Chosen.** Good zero-shot, permissive licence; wants the GPU backend |
| Qwen2.5-0.5B | ~0.52 GB | 251 / 30 (S24 Ultra) | – | Apache-2.0 | Fallback for CPU-only devices |
| Gemma 3 270M (FunctionGemma build) | ~0.29 GB | 2238 / 154 (S25 Ultra) | GPU support WIP | Gemma terms | Fastest, but needs fine-tuning; rejected |
| Gemma 3 1B | ~1.0 GB | 177 / 33 (S24 Ultra) | 1191 / 24 | Gemma terms | Best zero-shot of the small set; high-end only |
| Gemma 3n E2B | ~3.0 GB | 111 / 16 | 816 / 16 | Gemma terms | Too big for a browser add-on |

**Latency estimate** for one classification (prompt trimmed to ~200 tokens, ~10 output tokens):
- Qwen3-0.6B on GPU: ~0.35 s prefill + ~0.5 s decode ≈ **0.8–1 s** on a flagship. On CPU it is ~1.2 s prefill + ~1 s decode, about 2–3 s.
- Qwen2.5-0.5B on CPU (fallback): ~0.8 s prefill + ~0.3 s decode ≈ 1–1.5 s.

Because the output is short and constrained, prefill dominates. So we keep the feature payload tight (step 2 target: ≤200 tokens) and reuse a cached system-prompt prefix across calls if LiteRT-LM's session API allows it. Qwen3's thinking mode is disabled (`/no_think`) so it answers in a single short JSON object. One check per new site, with the candidate quarantined while it runs (section 3), makes a ~1 s delay acceptable.

**Why Qwen3-0.6B:** it is good enough to classify zero-shot, so step 2 doesn't depend on building a training set first. Its Apache-2.0 licence has no use policy. Model size and speed are the costs, and we mitigate them with the GPU backend, a short prompt and per-site caching. If step 4 shows accuracy or latency gaps, a LoRA fine-tune of the same model is the next lever.

## 5. Size, memory, battery

- **APK:** expect roughly 15–30 MB: Compose UI, `adblock-rust` (a few MB per ABI), and LiteRT-LM native libraries. These are estimates, to be confirmed in step 3. Ship an App Bundle so each device gets one ABI.
- **Model:** ~0.6 GB, downloaded after install (Wi-Fi by default) and stored in app-private storage. The browser works rules-only until the model arrives. Play's base-module limit (200 MB) makes bundling impractical anyway.
- **RAM:** load the model lazily on the first uncertain candidate and unload it after ~1–2 minutes idle (90 s in the app), and at once when the app leaves the screen (`onTrimMemory`), since a cached app is frozen before any timer runs. Never load it when the system reports low memory. Measured on a Galaxy Z Fold 7 (step 4): Qwen3-0.6B INT8 adds about 2.4 GB on the GPU (2.3 GB of it graphics buffers, which are system RAM on the phone) and 1.7 GB on the CPU; INT4 adds 1.4 GB and 0.8 GB.
- **Battery:** the model runs about once per new site and is cached afterwards, so the cost is small compared with page rendering. In battery-saver mode, skip the LLM and run rules plus heuristics only. Run inference on a background dispatcher and never on the UI thread.

## 6. Risks and open questions

- **False positives** (hiding real content, clicking the wrong button). Mitigations: the `content` label, a conservative threshold for `click`, a per-site undo, and a cached allow rule after an undo.
- **Latency on mid-range phones.** A CPU-only device could take 2–3 s per check. Measure in step 4. If it's too slow, switch those devices to Qwen2.5-0.5B or skip the LLM on them and run rules plus heuristics only.
- **WebView feature gaps.** Rare devices with very old WebView builds lack `DOCUMENT_START_SCRIPT`. The `onPageStarted` fallback is best-effort and may lose the race against page scripts.
- **Cross-origin CMP iframes.** Document-start scripts reach them only if their origin matches the allowed rules. We use `"*"` and rely on our own script being inert outside its job.
- **Evaluation data.** We need a labeled set and a repeatable crawl. Defining it is the first task of step 2.

## Sources

- MediaPipe LLM Inference guide (maintenance-only notice, supported models): https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference
- LiteRT-LM overview (APIs, backends, model table): https://developers.google.com/edge/litert-lm/overview
- androidx.webkit `WebViewCompat` reference: https://developer.android.com/reference/androidx/webkit/WebViewCompat
- adblock-rust: https://lib.rs/crates/adblock
