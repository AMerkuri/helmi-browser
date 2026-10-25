# Serene

An Android browser that uses a small on-device language model, together with community filter lists, to remove page annoyances: cookie banners, newsletter modals, notification pre-prompts, paywall teasers and similar overlays.

Status: MVP Android app. Stack decisions: [docs/decisions.md](docs/decisions.md). Engine design and results: [docs/engine.md](docs/engine.md), code in [engine/](engine). The app is in [android/](android): every PR and every push to `main` builds a debug APK you can download from the `android` workflow run and sideload.

Reader mode and page summaries (with the same on-device model): [docs/reader.md](docs/reader.md).
