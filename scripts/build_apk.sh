#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROBE_DIR="$ROOT_DIR/probe"

# Auto-detect JAVA_HOME if not set
if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]]; then
    export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  fi
fi

# Auto-detect Android SDK
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
if [[ ! -d "$SDK" && -d "$HOME/Android/Sdk" ]]; then
  SDK="$HOME/Android/Sdk"
fi

BT_VERSION="$(ls -1 "$SDK/build-tools" | sort -V | tail -n 1)"
PLATFORM_VERSION="$(ls -1 "$SDK/platforms" | sort -V | tail -n 1)"
BT="$SDK/build-tools/$BT_VERSION"
PLATFORM="$SDK/platforms/$PLATFORM_VERSION/android.jar"

echo "Building Aura TV APK using build-tools $BT_VERSION and $PLATFORM_VERSION..."

cd "$PROBE_DIR"
rm -rf build assets
mkdir -p build/gen build/obj build/dex build/res assets

# Optionally bundle .env into untracked assets/aura.env only when AURA_BUNDLE_ENV=1
if [[ "${AURA_BUNDLE_ENV:-0}" == "1" && -f "$ROOT_DIR/.env" ]]; then
  cp "$ROOT_DIR/.env" assets/aura.env
fi

# Generate debug.keystore if not present
if [[ ! -f debug.keystore ]]; then
  "${JAVA_HOME:+$JAVA_HOME/bin/}keytool" -genkeypair -v \
    -keystore debug.keystore -storepass android -alias androiddebugkey \
    -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US"
fi

"$BT/aapt2" compile --dir res -o build/res/compiled.zip
"$BT/aapt2" link -o build/unaligned.apk -I "$PLATFORM" \
  --manifest AndroidManifest.xml -A assets --java build/gen build/res/compiled.zip
rm -f assets/aura.env

"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -source 11 -target 11 -cp "$PLATFORM" \
  -d build/obj $(find src build/gen -name "*.java")

"$BT/d8" --lib "$PLATFORM" --output build/dex $(find build/obj -name "*.class")

cp build/dex/classes.dex build/classes.dex
(cd build && zip -q unaligned.apk classes.dex)
"$BT/zipalign" -f 4 build/unaligned.apk build/aligned.apk
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android \
  --key-pass pass:android --out aura-probe.apk build/aligned.apk

echo "Built APK successfully: $PROBE_DIR/aura-probe.apk"
