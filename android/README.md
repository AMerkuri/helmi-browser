# Serene for Android (MVP)

A small browser shell around the hybrid annoyance engine from [`engine/`](../engine): tabs, a URL bar, settings, a per-site switch and a "Report missed popup" button. Step 3 of the plan in [docs/decisions.md](../docs/decisions.md).

## Install a build

Every pull request and every push to `main` builds a debug APK in GitHub Actions (workflow `android`). On a pull request, the **Debug APK** section at the bottom of the PR description links straight to the latest build. Otherwise open the run and download the `serene-<run>-<sha>` artifact under **Artifacts**, unzip it on the phone and open the `.apk` (allow installs from your browser or file manager when Android asks). All builds are signed with the same debug key (`app/debug.keystore`, committed on purpose), so a newer build installs over the old one and keeps its settings.

After the first start:
- The filter lists download in the background (about 3 MB). Until they arrive, Serene runs on its heuristics alone.
- The model is optional. **Settings → On-device model → Download** fetches Qwen3-0.6B (586 MB, Wi-Fi only by default) from Hugging Face and checks its SHA-256. Without it, Serene uses lists and heuristics only, which handled nearly everything in the step 2 crawl.

## How the engine is wired in

| Engine piece (prototype) | App |
|---|---|
| `src/content/serene.js` | Packaged unchanged as an asset and injected with `WebViewCompat.addDocumentStartJavaScript(…, setOf("*"))` into every frame, after `dist/autoconsent.js` in the same script |
| `src/host/autoconsent.mjs` | [`AutoconsentRules.kt`](app/src/main/kotlin/app/serene/engine/AutoconsentRules.kt): DuckDuckGo autoconsent's rules (`engine/dist`, an asset) filtered per frame URL; [`AutoconsentRulesTest.kt`](app/src/test/kotlin/app/serene/engine/AutoconsentRulesTest.kt) checks it against autoconsent's own filter |
| `SereneBridge` (harness shim) | `WebViewCompat.addWebMessageListener(webView, "SereneBridge", setOf("*"))`; replies go through the frame's `JavaScriptReplyProxy` |
| `context.route` | `WebViewClient.shouldInterceptRequest` → `RulesEngine.shouldBlock`, returning an empty response |
| `src/host/rules.mjs` (adblock-rs) | [`rules-jni/`](rules-jni): the same adblock-rust 0.13.3 crate over JNI, built with `cargo-ndk`; the parsed engine is cached on disk |
| `src/host/policy.mjs` | [`Policy.kt`](app/src/main/kotlin/app/serene/engine/Policy.kt), same regexes and thresholds; [`PolicyTest.kt`](app/src/test/kotlin/app/serene/engine/PolicyTest.kt) mirrors `policy.test.mjs` |
| `src/host/classifier.mjs` | [`Prompt.kt`](app/src/main/kotlin/app/serene/engine/Prompt.kt) (same prompt and schema) + [`Classifier.kt`](app/src/main/kotlin/app/serene/engine/Classifier.kt): LiteRT-LM 0.18.1, GPU backend with CPU fallback, JSON-schema constrained decoding, thinking off, unloaded after 90 s idle. The prototype's single-pass label scoring is not ported (LiteRT-LM's Kotlin API has no scoring call), so there is no confidence and the policy acts on the label |
| `src/host/cache.mjs` | [`VerdictCache.kt`](app/src/main/kotlin/app/serene/engine/VerdictCache.kt), a JSON file keyed by eTLD+1 |
| `src/host/engine.mjs` | [`SereneEngine.kt`](app/src/main/kotlin/app/serene/engine/SereneEngine.kt): one `PageSession` per tab navigation |
| `src/host/lists.mjs` | [`FilterLists.kt`](app/src/main/kotlin/app/serene/engine/FilterLists.kt), refreshed every 4 days; it also builds the small cookie-rules engine (`RulesEngine.consent`) that marks which hide selectors to park for autoconsent |

When the policy or prompt changes in `engine/src/host/`, change the Kotlin port in the same PR; the unit tests are a line-by-line mirror of the prototype's.

The model is skipped in battery saver (a setting), when the system reports low memory, and when it is not downloaded. Then the engine decides with lists and heuristics only, as in the prototype's rules-only mode.

## The app

- **Shield** (left of the URL bar): what Serene did on this page (requests blocked, overlays handled and how), **Undo** for each action, the per-site switch, "Forget this site" and "Report missed popup".
- **Report missed popup** (also in the menu): saves `report.json` (what the detector sees on screen right now, every candidate and verdict on the page, the engine log, app/WebView versions) and a screenshot, and asks for an optional note. Then either:
  - **GitHub issue**: opens the [missed-popup issue form](../.github/ISSUE_TEMPLATE/missed-popup.yml) in a new tab with every field filled in (page address without its query string, the verdicts, the log tail, a trimmed HTML snippet of what is on screen; trimmed to keep the URL under 8 KB). You submit it from your own GitHub login, so the app holds no token. A link cannot carry files, so the screenshot goes to the clipboard and to Pictures/Serene (Android 10+), to paste or attach in the form's Screenshot box.
  - **Share files**: the share sheet with `report.json` and the screenshot.

  Nothing leaves the phone unless you submit or send it.
- **Settings**: Serene on/off, filter list status and update, forget learned site rules, model download/choice/delete, Wi-Fi-only, battery saver, sites where Serene is off, search engine.
- **Downloads**: Serene fetches files itself, with the page's cookies (so signed-in links such as GitHub Actions artifacts work), and saves them to the phone's Downloads folder. A tab opened just for a file shows a download page like Chrome's (name, MB done / total, time left, pause/resume, cancel, Open when done); a link on a page keeps the page and shows the same row in a bar at the bottom. Pausing resumes later with an HTTP Range request. **Menu → Downloads** lists every download; a notification shows progress while one runs and opens the file when it finishes. Opening a downloaded `.apk` hands it to the installer, which asks you to allow installs from Serene the first time.
- **Tabs**: the tab button shows open tabs as Cover Flow (iTunes and the first iPhones): a picture of each page, the middle one facing you and the rest turned towards it, with a reflection on a black floor. Drag or fling sideways to flip through; tap a card at the side to bring it to the middle and the middle one to open it, which grows the card back into the page. Push the middle card up (or tap its ✕) to close the tab. Pictures are taken with PixelCopy when you leave a tab or open the switcher, kept at half resolution (64 MB across tabs) and saved over a restart.
- Serene can be set as the default browser (it handles `http`/`https` links). Open tabs are restored on the next start.

## Building locally

Needs JDK 17+, the Android SDK with NDK `29.0.14206865`, Rust with `rustup target add aarch64-linux-android x86_64-linux-android`, and `cargo install cargo-ndk`.

```sh
cd android
./gradlew :app:assembleDebug          # also builds rules-jni through cargo-ndk
./gradlew :app:testDebugUnitTest      # policy/prompt/cache unit tests (JVM)
./gradlew :app:connectedDebugAndroidTest   # fixture pages on a device or emulator
cd rules-jni && cargo test --release  # rules engine on the host
```

`FixturesTest` runs the prototype's fixture pages (`engine/test/fixtures`) in the app on a device, with the same expectations as `engine/test/fixtures.test.mjs` and the same stubbed model. CI runs it on an API 34 emulator after every build.

## Known gaps (MVP)

- Not measured on a phone yet: model latency and memory on LiteRT-LM, page-load overhead, battery. That is step 4.
- WebView does not report a request's type, so `shouldInterceptRequest` guesses it from the `Accept` header and file extension (`*/*` counts as a script). Service-worker requests are not filtered.
- The Qwen2.5-0.5B CPU fallback from the decision doc has no `.litertlm` build on litert-community, so the second choice is Qwen3-0.6B INT4 (329 MB). Its accuracy has not been measured.
- Each model call starts a fresh LiteRT-LM conversation, so the system prompt is prefilled every time (about 150 tokens). Reusing a prefilled session is a step 4 item.
- No incognito mode, history, bookmarks, file uploads or full-screen video yet.
- Release signing and an App Bundle are not set up; CI produces debug builds only.
