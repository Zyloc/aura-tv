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

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
PLATFORM_VERSION="$(ls -1 "$SDK/platforms" | sort -V | tail -n 1)"
PLATFORM="$SDK/platforms/$PLATFORM_VERSION/android.jar"

STUDIO_DIR="${ANDROID_STUDIO_DIR:-/Applications/Android Studio.app/Contents}"
if [[ ! -d "$STUDIO_DIR" && -d "/opt/android-studio" ]]; then
  STUDIO_DIR="/opt/android-studio"
fi
JUNIT_JAR="${JUNIT_JAR:-$STUDIO_DIR/lib/junit4.jar}"
LAYOUTLIB_JAR="${LAYOUTLIB_JAR:-$STUDIO_DIR/plugins/design-tools/lib/layoutlib.jar}"

# 1. Ensure main APK classes are compiled
"$ROOT_DIR/scripts/build_apk.sh"

# 2. Compile Unit & Integration Tests
mkdir -p "$PROBE_DIR/build/test-obj"
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -source 11 -target 11 \
  -cp "$JUNIT_JAR:$LAYOUTLIB_JAR:$PLATFORM:$PROBE_DIR/build/obj" \
  -d "$PROBE_DIR/build/test-obj" \
  $(find "$PROBE_DIR/test" -name "*.java")

# 3. Execute Unit & Integration Test Suite on JVM
"${JAVA_HOME:+$JAVA_HOME/bin/}java" \
  -cp "$PROBE_DIR/build/test-obj:$PROBE_DIR/build/obj:$JUNIT_JAR:$LAYOUTLIB_JAR:$PLATFORM" \
  com.aura.tvprobe.AuraTestSuiteRunner

# 4. Optional Live TV Device Integration Check (if TV is connected over ADB)
if [[ "${AURA_SKIP_TV_CHECK:-0}" != "1" ]] && adb devices 2>/dev/null | grep -q "device$"; then
  TV_SERIAL="$(adb devices | awk '/device$/{print $1; exit}')"
  echo "Running Live TV Device Integration Check on $TV_SERIAL..."
  if adb -s "$TV_SERIAL" shell pm path com.aura.tvprobe >/dev/null 2>&1; then
    echo "  [PASS] LiveTVPackageAndHealthCheck ($TV_SERIAL has com.aura.tvprobe installed)"
  fi
fi
