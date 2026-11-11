#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ -z "${AURA_TV_IP:-}" && -f "$ROOT_DIR/.env" ]]; then
  AURA_TV_IP="$(grep -E '^AURA_TV_IP=' "$ROOT_DIR/.env" | head -n 1 | cut -d= -f2- | tr -d '"' | tr -d "'" | xargs || true)"
fi

TV_IP="${AURA_TV_IP:-}"
if [[ -z "$TV_IP" ]]; then
  echo "Error: Set AURA_TV_IP in .env or environment before running deploy_tv.sh"
  exit 1
fi

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ADB="${ADB:-$SDK/platform-tools/adb}"

AURA_BUNDLE_ENV="${AURA_BUNDLE_ENV:-1}" "$ROOT_DIR/scripts/build_apk.sh"

echo "Connecting to Android TV at $TV_IP:5555..."
"$ADB" connect "$TV_IP:5555"
TV_BRAND="$("$ADB" -s "$TV_IP:5555" shell getprop ro.product.brand | tr -d '\r' || true)"
TV_MODEL="$("$ADB" -s "$TV_IP:5555" shell getprop ro.product.model | tr -d '\r' || true)"
TV_SDK="$("$ADB" -s "$TV_IP:5555" shell getprop ro.build.version.sdk | tr -d '\r' || true)"
TV_LOCALE="$("$ADB" -s "$TV_IP:5555" shell getprop persist.sys.locale | tr -d '\r' || true)"
echo "Detected Target TV: Brand=${TV_BRAND:-Unknown} Model=${TV_MODEL:-Unknown} SDK=${TV_SDK:-?} Locale=${TV_LOCALE:-auto}"
PRE_INSTALL_FG="$("$ADB" -s "$TV_IP:5555" shell dumpsys window windows | grep -E "mCurrentFocus|mFocusedApp" || true)"
"$ADB" -s "$TV_IP:5555" install -r "$ROOT_DIR/probe/aura-probe.apk"

# Grant TV permissions & notification listener access for MediaSession inspection
"$ADB" -s "$TV_IP:5555" shell pm grant com.aura.tvprobe android.permission.READ_TV_LISTINGS || true
"$ADB" -s "$TV_IP:5555" shell pm grant com.aura.tvprobe android.permission.RECORD_AUDIO || true
"$ADB" -s "$TV_IP:5555" shell pm grant com.aura.tvprobe android.permission.READ_LOGS || true
"$ADB" -s "$TV_IP:5555" shell cmd notification allow_listener com.aura.tvprobe/com.aura.tvprobe.MediaProbeService || true
"$ADB" -s "$TV_IP:5555" shell appops set com.aura.tvprobe GET_USAGE_STATS allow || true
"$ADB" -s "$TV_IP:5555" shell appops set com.aura.tvprobe SYSTEM_ALERT_WINDOW allow || true

if [[ "${AURA_OPTIMIZE_TV_WIFI:-0}" == "1" || "${1:-}" == "--optimize-wifi" ]]; then
  bash "$ROOT_DIR/scripts/optimize_tv_wifi.sh" "$TV_IP"
fi

"$ADB" -s "$TV_IP:5555" shell cmd package set-home-activity com.aura.tvprobe/.AuraActivity || true

if [[ "${FORCE_LAUNCH:-0}" == "1" ]] || echo "$PRE_INSTALL_FG" | grep -q "com.aura.tvprobe" || ! echo "$PRE_INSTALL_FG" | grep -Eq "hotstar|netflix|youtube|appletv|amazonvideo|sonyliv|jiocinema"; then
  "$ADB" -s "$TV_IP:5555" shell input keyevent KEYCODE_HOME
fi
echo "Deployed Aura TV on $TV_IP"
