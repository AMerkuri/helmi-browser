# Payments (Payment Request API)

Chrome on Android gives pages `PaymentRequest`, and checkout buttons such as Google Pay use it to
open a payment app on the phone. Android WebView has the same API since WebView 136, off by
default. Helmi turns it on, with a switch in **Settings › Flags › Payment Request** to turn it off.

## What Helmi does

- `android/.../browser/Tab.kt` (`applyPayments`) calls `WebSettingsCompat.setPaymentRequestEnabled`
  with the flag, in every tab, when the installed WebView has the `PAYMENT_REQUEST` feature
  (androidx.webkit 1.14+). The setting applies to pages loaded after a change: a page has the API
  or not from its start.
- The manifest's `<queries>` names the three payment intents (`org.chromium.intent.action.PAY`,
  `IS_READY_TO_PAY`, `UPDATE_PAYMENT_DETAILS`). Without them Android 11+ hides the payment apps
  from WebView.
- Under the flag, **Allow sites to check if you have payment methods saved** (on by default, as in
  Chrome) maps to `setHasEnrolledInstrumentEnabled`: off, sites can't ask a payment app whether it
  has a card ready (`hasEnrolledInstrument()`).
- Settings › About says "payments supported" when the WebView has the feature.

## What works and what doesn't

- **Android payment apps only.** WebView opens apps that answer the PAY intent. It has no
  web-based payment apps (service-worker payment handlers) and no Secure Payment Confirmation.
  `basic-card` is gone from Chromium altogether.
- **Google Pay** needs Google Play services 25.18.30+ and WebView 137+, and Google's guide says the
  host app must be allowed: "In order for your app to be allowed to use Google Pay within Android
  WebView you need to publish your App integration"
  ([Using Android WebView](https://developers.google.com/pay/api/android/guides/recipes/using-android-webview)).
  That process is written for a merchant's own app, not a browser. Google Pay serves Helmi anyway:
  on the Galaxy Fold 7 (2026-11-02) the demo below showed its button and a tap opened the Google
  Pay sheet. Should Google start refusing unlisted apps, `IS_READY_TO_PAY` answers false and sites
  hide their Google Pay button, as with the passkey allowlist ([passkeys.md](passkeys.md)); the
  flag off then brings back pay.js's web flow.
- **With the flag off,** pages have no `PaymentRequest`. Google's pay.js then uses its web flow (a
  pay.google.com window, which Helmi opens as a tab with an opener), and other sites show their
  own card form. With it on, pay.js uses the Android sheet instead.
- **Desktop site** (⋮ › Desktop site) sends a desktop user agent; Google's guide asks custom user
  agents to append `GOOGLE_PAY_SUPPORTED`. Helmi doesn't yet: desktop pages are expected to use
  Google Pay's desktop (web) flow.
- Requests go through `shouldInterceptRequest` only to block them; Helmi never fetches a page
  with its own HTTP client, which the Google Pay guide says isn't supported.

## Checking it on a phone

Open <https://gpay-live-demo.web.app/basic.html>. It shows a Google Pay button only when Google
Pay says it can pay, and a tap opens the Google Pay sheet (with the flag off, pay.js's web flow;
reload after switching). Close the sheet before confirming anything. The CI emulator's WebView is
older than 136, so `PaymentRequestTest` skips there and a phone is the real check.

## Privacy

`IS_READY_TO_PAY` can tell a site which payment apps are installed and whether one has a card,
which is why the check has its own switch. Payment apps are expected to throttle those answers.
WebView runs in Helmi's process, so Helmi can't limit which apps a page reaches; it's the same
set Chrome reaches.
