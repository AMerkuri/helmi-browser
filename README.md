# Serene

An Android browser that uses a small on-device language model, together with community filter lists, to remove page annoyances: cookie banners, newsletter modals, notification pre-prompts, paywall teasers and similar overlays.

Status: MVP Android app. Stack decisions: [docs/decisions.md](docs/decisions.md). Engine design and results: [docs/engine.md](docs/engine.md), code in [engine/](engine). The app is in [android/](android): every PR and every push to `main` builds a debug APK you can download from the `android` workflow run and sideload.

Reader mode: [docs/reader.md](docs/reader.md).

License: [GPL-3.0](LICENSE). The app bundles [autoconsent](https://github.com/duckduckgo/autoconsent) (MPL-2.0), [Readability](https://github.com/mozilla/readability) (Apache-2.0) and [adblock-rust](https://github.com/brave/adblock-rust) (MPL-2.0), each under its own license.
