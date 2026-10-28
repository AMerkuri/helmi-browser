# Releases

Releases are signed APKs on the repo's [Releases page](https://github.com/AMerkuri/helmi-browser/releases), built by the [`release` workflow](../.github/workflows/release.yml). Pull requests keep getting debug APKs from the `android` workflow, as before.

| | Release | Debug (PR builds) |
|---|---|---|
| App id | `app.helmi.browser` | `app.helmi.browser.debug` |
| Name on the phone | Helmi | Helmi Debug |
| Signed with | the private release key (Actions secrets) | `android/app/debug.keystore`, committed |
| Version | the tag (`v0.2.0` is 0.2.0), build = release run number | 0.1.0-debug, build = `android` run number |
| Where | GitHub Releases, kept | Actions artifacts, 30 days |

The two are separate apps, so both can be installed side by side, each with its own settings, tabs and downloaded models. They can't update each other anyway, because Android only installs an update signed with the same key.

## One-time setup: the release key

Make the key on your own computer, so it never passes through CI logs or a chat. `keytool` comes with any JDK (Android Studio has one in `jbr/bin`).

```sh
keytool -genkeypair -v -keystore helmi-release.jks -alias helmi -keyalg RSA -keysize 4096 -validity 10000
```

It asks for a password (once, for both the keystore and the key) and your name and country. RSA 4096 and 10000 days (about 27 years) are what Google Play asks of a signing key, so the same key can sign Play builds later (see below).

Then add three repository secrets under **Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE` | the keystore as base64, one line: `base64 < helmi-release.jks \| tr -d '\n'` (Windows PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("helmi-release.jks"))`) |
| `RELEASE_KEYSTORE_PASSWORD` | the password you typed |
| `RELEASE_KEY_ALIAS` | `helmi` |

Or with the GitHub CLI: `base64 < helmi-release.jks | tr -d '\n' | gh secret set RELEASE_KEYSTORE`, then `gh secret set RELEASE_KEYSTORE_PASSWORD` and `gh secret set RELEASE_KEY_ALIAS --body helmi`.

**Back up `helmi-release.jks` and its password** somewhere outside the repo (a password manager takes file attachments). If the key is lost, no new release can update an installed Helmi: everyone would have to uninstall, and lose their settings, to move to a build signed with a new key. Never commit it.

## Cutting a release

Either push a tag:

```sh
git switch main && git pull
git tag v0.2.0
git push origin v0.2.0
```

or open **Actions → release → Run workflow**, pick the branch and type the version (`0.2.0`); the workflow tags that branch's latest commit.

The workflow checks the secrets, builds `:app:assembleRelease` with that version, verifies the APK is signed with the release key (and not the debug key), and publishes the release `Helmi 0.2.0` with `helmi-0.2.0.apk`, its `.sha256` and notes generated from the merged PRs since the last release. It takes about 5 to 8 minutes. A version with a suffix, such as `v0.2.0-beta.1`, is published as a pre-release.

Versions must go up: `versionCode` is the release workflow's run number, so every release, even one built from an older tag, counts as newer than the one before it.

## Installing

Download the APK from the release on the phone and open it. To get updates automatically, add `https://github.com/AMerkuri/helmi-browser` in [Obtainium](https://github.com/ImranR98/Obtainium); it reads the Releases page. The release notes give the signing certificate's SHA-256, which an app such as AppVerifier can check against the installed app.

A phone that has an `app.helmi.browser` debug build from before this flow (named plain "Helmi", signed with the debug key) has to uninstall it once before installing the first release.

## Later: Google Play

Play signs every app with Play App Signing. When setting it up, choose to use your existing app signing key and upload this same key (Play Console gives a tool, PEPK, that encrypts it for upload). Play builds and GitHub releases then carry the same signature, so people can switch between them without uninstalling, and Google's passkey allowlisting ([passkeys.md](passkeys.md)) sees a single fingerprint. Google keeps a copy of the key from then on; you upload builds with a second "upload key", which Google can reset if it's lost. Play also wants an App Bundle (`./gradlew :app:bundleRelease`) rather than an APK, and the app id can never change after the first upload.

If Play generates its own key instead, Play builds and GitHub releases have different signatures, and switching between them means uninstalling.

## Not done yet

- R8 (shrinking and obfuscation) is off. LiteRT-LM, the JNI rules engine and kotlinx.serialization would need keep rules, and the emulator tests only run on the debug build, so a minified release would ship untested.
- The release build runs no tests of its own: it is built from a commit whose debug build already passed the `android` workflow (unit and emulator tests) on its PR and on `main`.
