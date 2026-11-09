#!/usr/bin/env bash
set -euo pipefail

# Optional Android TV (Xiaomi Mi TV / MediaTek MT7668) Wi-Fi & Looper stall optimizations.
# Invoked when AURA_OPTIMIZE_TV_WIFI=1 or --optimize-wifi is passed to deploy_tv.sh.

TV_IP="${1:-${AURA_TV_IP:-}}"
if [[ -z "$TV_IP" ]]; then
  echo "Usage: $0 <TV_IP>"
  exit 1
fi

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ADB="${ADB:-$SDK/platform-tools/adb}"

"$ADB" -s "$TV_IP:5555" shell settings put global wifi_score_params "rssi2=-95:-92:-85:-70,rssi5=-95:-92:-88:-78,pps=0:1:100,horizon=15,nud=8,expid=0" || true
"$ADB" -s "$TV_IP:5555" shell settings put global wifi_scan_always_enabled 0 || true
"$ADB" -s "$TV_IP:5555" shell settings put global ble_scan_always_enabled 0 || true
"$ADB" -s "$TV_IP:5555" shell settings put global wifi_wakeup_enabled 0 || true
"$ADB" -s "$TV_IP:5555" shell settings put global wifi_suspend_optimizations_enabled 0 || true
"$ADB" -s "$TV_IP:5555" shell settings put secure location_mode 0 || true
"$ADB" -s "$TV_IP:5555" shell settings put secure location_providers_allowed -gps || true
"$ADB" -s "$TV_IP:5555" shell settings put secure location_providers_allowed -network || true
"$ADB" -s "$TV_IP:5555" shell cmd appops set com.google.android.gms WIFI_SCAN ignore || true
"$ADB" -s "$TV_IP:5555" shell cmd appops set com.google.android.gms CHANGE_WIFI_STATE ignore || true
"$ADB" -s "$TV_IP:5555" shell cmd appops set com.google.android.katniss WIFI_SCAN ignore || true
"$ADB" -s "$TV_IP:5555" shell cmd appops set com.google.android.katniss FINE_LOCATION ignore || true
"$ADB" -s "$TV_IP:5555" shell cmd appops set com.google.android.katniss COARSE_LOCATION ignore || true
"$ADB" -s "$TV_IP:5555" shell pm revoke com.google.android.katniss android.permission.ACCESS_FINE_LOCATION || true
"$ADB" -s "$TV_IP:5555" shell pm revoke com.google.android.katniss android.permission.ACCESS_COARSE_LOCATION || true
"$ADB" -s "$TV_IP:5555" shell cmd appops set com.google.android.apps.nbu.smartconnect.tv WIFI_SCAN ignore || true
"$ADB" -s "$TV_IP:5555" shell cmd notification disallow_listener com.google.android.tvrecommendations/com.google.android.tvrecommendations.NotificationsService || true
"$ADB" -s "$TV_IP:5555" shell pm disable-user --user 0 com.google.android.tvrecommendations || true
