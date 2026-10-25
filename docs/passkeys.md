# Passkeys

Serene turns on WebView's browser WebAuthn mode in every tab
(`WebSettingsCompat.setWebAuthenticationSupport(..., WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER)`,
in `android/.../browser/Tab.kt`). Pages then see `PublicKeyCredential`, and WebView sends
`navigator.credentials.create()` and `get()` to Android's Credential Manager with the page's
origin, the way Chrome does. Without it, WebView hides WebAuthn and sites say so (GitHub:
"Webauthn isn't supported").

## What it needs

- **WebView with the `WEB_AUTHENTICATION` feature.** Settings → About says "passkeys supported"
  when the installed WebView has it. Without it, Serene behaves as before.
- **Android 14 or later.** WebView only routes browser-mode requests through Credential Manager
  from Android 14 (`CredManSupportProvider.getCredManSupportForWebView` in Chromium).
- **`CREDENTIAL_MANAGER_SET_ORIGIN`** in the manifest. It is a normal permission, so every
  install gets it; it is what lets WebView put a site's origin on the request.
- **A passkey provider that trusts Serene as a browser.** This is the part the app can't do
  itself. A provider that hands out passkeys for any origin must be sure the caller is a real
  browser, so each provider keeps a list of privileged apps, by package name and signing
  certificate:
  - **Google Password Manager** uses Google's list
    (<https://www.gstatic.com/gpm-passkeys-privileged-apps/apps.json>). Serene isn't on it, so
    Google Password Manager passkeys don't work in Serene yet. Getting on it means applying
    through the form on
    [Privileged apps](https://developer.android.com/identity/sign-in/privileged-apps), with a
    release signing key that only we hold. The committed `debug.keystore` can't be that key:
    it is public, so anyone could sign a phishing app that Google would then trust.
  - **Bitwarden** asks the first time an unknown browser makes a passkey request: pick
    "Trust" and it adds Serene to the "Trusted by You" part of its privileged apps list. Make
    Bitwarden a passkey provider first, on the passwords and passkeys page of Android Settings
    (on Samsung: General management → Passwords, passkeys and autofill).
  - **Dashlane** builds a fixed list of about 36 browsers into the app (a copy of Google's,
    `PasskeyPrivilegedApplications.kt` in its
    [open-source Android app](https://github.com/Dashlane/android-apps)) and answers any other
    caller with "Unsupported origin". There is no way to trust a browser by hand, so GitHub shows
    "Authentication failed". Tried on a Galaxy Fold 7 (WebView 155), 2026-10-25. Getting listed
    needs the same release key, plus a request to Dashlane.
  - Other providers (Samsung Pass, 1Password, Proton Pass, KeePassDX) each decide this
    themselves; none has been tried yet.

## Testing

`PasskeyTest` (androidTest) checks that a tab is in browser mode and a page sees
`PublicKeyCredential`. It skips on a WebView without the feature. The emulator has no passkey
provider, so signing in is a manual check on a phone, for example GitHub's two-factor page or
<https://webauthn.io>.
