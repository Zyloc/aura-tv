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

"$ROOT_DIR/scripts/build_apk.sh"

echo "Connecting to Android TV at $TV_IP:5555..."
"$ADB" connect "$TV_IP:5555"
"$ADB" -s "$TV_IP:5555" install -r "$ROOT_DIR/probe/aura-probe.apk"

# Grant TV permissions & notification listener access for MediaSession inspection
"$ADB" -s "$TV_IP:5555" shell pm grant com.aura.tvprobe android.permission.READ_TV_LISTINGS || true
"$ADB" -s "$TV_IP:5555" shell pm grant com.aura.tvprobe android.permission.RECORD_AUDIO || true
"$ADB" -s "$TV_IP:5555" shell cmd notification allow_listener com.aura.tvprobe/com.aura.tvprobe.MediaProbeService || true
"$ADB" -s "$TV_IP:5555" shell appops set com.aura.tvprobe GET_USAGE_STATS allow || true
"$ADB" -s "$TV_IP:5555" shell appops set com.aura.tvprobe SYSTEM_ALERT_WINDOW allow || true
"$ADB" -s "$TV_IP:5555" shell settings put global wifi_score_params "rssi2=-95:-92:-85:-70,rssi5=-95:-92:-88:-78,pps=0:1:100,horizon=15,nud=8,expid=0" || true
"$ADB" -s "$TV_IP:5555" shell settings put global wifi_scan_always_enabled 0 || true
"$ADB" -s "$TV_IP:5555" shell settings put global ble_scan_always_enabled 0 || true
"$ADB" -s "$TV_IP:5555" shell settings put global wifi_wakeup_enabled 0 || true
"$ADB" -s "$TV_IP:5555" shell settings put global wifi_suspend_optimizations_enabled 0 || true
"$ADB" -s "$TV_IP:5555" shell cmd appops set com.google.android.gms WIFI_SCAN ignore || true
"$ADB" -s "$TV_IP:5555" shell cmd appops set com.google.android.katniss WIFI_SCAN ignore || true
"$ADB" -s "$TV_IP:5555" shell cmd appops set com.google.android.apps.nbu.smartconnect.tv WIFI_SCAN ignore || true
"$ADB" -s "$TV_IP:5555" shell cmd package set-home-activity com.aura.tvprobe/.AuraActivity || true

FOREGROUND="$("$ADB" -s "$TV_IP:5555" shell dumpsys window windows | grep -E "mCurrentFocus|mFocusedApp" || true)"
if [[ "${FORCE_LAUNCH:-0}" == "1" ]] || ! echo "$FOREGROUND" | grep -Eq "hotstar|netflix|youtube|appletv|amazonvideo|sonyliv|jiocinema"; then
  "$ADB" -s "$TV_IP:5555" shell am start -a android.intent.action.MAIN -c android.intent.category.HOME -n com.aura.tvprobe/.AuraActivity
fi
echo "Deployed Aura TV on $TV_IP"
