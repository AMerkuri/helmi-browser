#!/bin/bash
# Installs what the Android build needs in a Claude Code cloud container (Android SDK and NDK,
# Rust Android targets, cargo-ndk, a Maven Central mirror), so a session can run
#   cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest
# itself. Run it from the environment's setup script: cd helmi-browser && android/scripts/cloud-setup.sh
# Versions come from android/app/build.gradle.kts and android.yml, so a bump there is picked up here.
# Safe to run again: each step is skipped when already done. Emulator tests still need CI (no KVM).
set -euo pipefail

cd "$(dirname "$0")/../.."
gradle_file=android/app/build.gradle.kts
compile_sdk=$(sed -n 's/^ *compileSdk = \([0-9]*\).*/\1/p' "$gradle_file")
ndk=$(sed -n 's/^ *ndkVersion = "\(.*\)".*/\1/p' "$gradle_file")
cargo_ndk=$(sed -n 's/.*cargo install cargo-ndk --locked --version \([0-9.]*\).*/\1/p' .github/workflows/android.yml)

export ANDROID_HOME=${ANDROID_HOME:-/opt/android-sdk}
sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

if [ ! -x "$sdkmanager" ]; then
  tmp=$(mktemp -d)
  curl -sSfL -o "$tmp/clt.zip" https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
  unzip -q "$tmp/clt.zip" -d "$tmp"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf "$tmp"
fi
if [ ! -d "$ANDROID_HOME/platforms/android-$compile_sdk.0" ] || [ ! -d "$ANDROID_HOME/ndk/$ndk" ]; then
  yes | "$sdkmanager" --licenses > /dev/null || true
  "$sdkmanager" "platforms;android-$compile_sdk.0" "platform-tools" "ndk;$ndk" > /dev/null
fi

rustup target add aarch64-linux-android x86_64-linux-android > /dev/null 2>&1
if [ "$(cargo ndk --version 2>/dev/null | awk '{print $2}')" != "$cargo_ndk" ]; then
  cargo install cargo-ndk --locked --version "$cargo_ndk" > /dev/null 2>&1
fi

# Maven Central answers 429 to cloud containers once Gradle fetches in parallel; Google's
# mirror of it goes ahead of the repositories in settings.gradle.kts.
mkdir -p ~/.gradle/init.d
cat > ~/.gradle/init.d/maven-mirror.gradle <<'GRADLE'
def mirror = 'https://maven-central.storage-download.googleapis.com/maven2/'
beforeSettings { settings ->
    settings.pluginManagement.repositories { maven { name = 'MavenCentralMirror'; url = mirror } }
    settings.dependencyResolutionManagement.repositories { maven { name = 'MavenCentralMirror'; url = mirror } }
}
GRADLE

# Gradle finds the SDK here; the session's shell does not inherit the setup script's variables.
echo "sdk.dir=$ANDROID_HOME" > android/local.properties
echo "Android build tools ready: SDK $compile_sdk, NDK $ndk, cargo-ndk $cargo_ndk"
