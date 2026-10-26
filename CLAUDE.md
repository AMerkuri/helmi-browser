# Serene

Android browser that removes page annoyances (cookie banners, newsletter boxes, app banners, paywall teasers) with filter lists, an overlay detector and a small on-device model. Read [README.md](README.md), then [docs/engine.md](docs/engine.md) for how the engine decides.

## Layout

- `engine/`: the engine in JS. `src/content/serene.js` is the content script (it ships in the app unchanged); `src/host/` is the host side (rules, policy, cache, prompt). Fixture pages with their expected verdicts are in `test/fixtures/`.
- `android/`: the app (Kotlin, Compose, WebView). `app/src/main/kotlin/app/serene/engine/` is the Kotlin port of `engine/src/host` (`Policy.kt` mirrors `policy.mjs`, `PolicyTest.kt` mirrors `policy.test.mjs`). `rules-jni/` is adblock-rust over JNI.
- `.github/ISSUE_TEMPLATE/missed-popup.yml`: the form the app's "Report missed popup" fills in. Its field ids are the query parameters set in `android/.../report/IssueReport.kt`, so rename them in both places.

## Rules

- `cd engine && npm ci && npm test` before pushing an engine change. `npm test` rebuilds `engine/dist/`, which the app packages; commit dist with the change, because CI fails when it is stale.
- A change to `engine/src/host` gets the same change in the Kotlin port, in the same PR.
- Fixtures are hand-written imitations of an overlay pattern, never copies of a real site.
- A false positive (hiding real page content) is worse than a missed popup.
- The Android build needs the SDK, NDK and Rust; leave it to the `android` workflow, which builds a debug APK on every PR that touches the app (or the engine files it packages) and links it in the PR description.
- Open a PR as a draft while you are still pushing to it, and mark it ready for review when it is done. The emulator e2e job skips draft PRs (it is the biggest part of the Actions bill), so a PR must be ready for review before it can count as tested.
