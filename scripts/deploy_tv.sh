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
"$ADB" -s "$TV_IP:5555" shell settings put global wifi_score_params "rssi2=-90:-88:-75:-60,rssi5=-90:-88:-84:-72,pps=0:1:100,horizon=15,nud=8,expid=0" || true
"$ADB" -s "$TV_IP:5555" shell cmd package set-home-activity com.aura.tvprobe/.AuraActivity || true

"$ADB" -s "$TV_IP:5555" shell am start -n com.aura.tvprobe/.AuraActivity
echo "Deployed and launched Aura TV on $TV_IP"
