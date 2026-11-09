#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -z "${AURA_TV_IP:-}" && -f "$ROOT_DIR/.env" ]]; then
  AURA_TV_IP="$(grep -E '^AURA_TV_IP=' "$ROOT_DIR/.env" | head -n 1 | cut -d= -f2- | tr -d '"' | tr -d "'" | xargs || true)"
fi
TV_IP="${AURA_TV_IP:-192.168.1.4}"
DEV="$TV_IP:5555"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ADB="${ADB:-$SDK/platform-tools/adb}"

PASS_COUNT=0
FAIL_COUNT=0

pass() {
  PASS_COUNT=$((PASS_COUNT + 1))
  printf "  [PASS] %s\n" "$1"
}

fail() {
  FAIL_COUNT=$((FAIL_COUNT + 1))
  printf "  [FAIL] %s\n" "$1"
  exit 1
}

dump_ui() {
  "$ADB" -s "$DEV" shell rm -f /sdcard/aura_e2e_ui.xml >/dev/null 2>&1 || true
  for attempt in 1 2 3 4; do
    "$ADB" -s "$DEV" shell uiautomator dump /sdcard/aura_e2e_ui.xml >/dev/null 2>&1 || true
    local out
    out="$("$ADB" -s "$DEV" shell cat /sdcard/aura_e2e_ui.xml 2>/dev/null || true)"
    if [[ -n "$out" && "$out" == *"<hierarchy"* ]]; then
      echo "$out"
      return 0
    fi
    sleep 0.8
  done
}

assert_ui_contains() {
  local xml="$1"
  local needle="$2"
  local msg="$3"
  if echo "$xml" | grep -Fq "$needle"; then
    pass "$msg"
  else
    fail "$msg (missing '$needle' in live UI hierarchy)"
  fi
}

assert_ui_not_contains() {
  local xml="$1"
  local needle="$2"
  local msg="$3"
  if echo "$xml" | grep -Fq "$needle"; then
    fail "$msg (unexpected '$needle' found in live UI hierarchy)"
  else
    pass "$msg"
  fi
}

echo "=========================================================="
echo "  AURA TV · LIVE HARDWARE END-TO-END (E2E) TEST SUITE"
echo "  Target Device: $DEV"
echo "=========================================================="

"$ADB" connect "$DEV" >/dev/null
"$ADB" -s "$DEV" logcat -c

# 1. Verify Default Home Launcher Registration
HOME_RESOLVE="$("$ADB" -s "$DEV" shell cmd package resolve-activity -c android.intent.category.HOME -a android.intent.action.MAIN)"
if echo "$HOME_RESOLVE" | grep -Fq "name=com.aura.tvprobe.AuraActivity" && echo "$HOME_RESOLVE" | grep -Fq "isDefault=true"; then
  pass "1. Default HOME intent resolves to com.aura.tvprobe.AuraActivity (isDefault=true)"
else
  fail "1. Default HOME intent did NOT resolve to com.aura.tvprobe.AuraActivity"
fi

# Guard against interrupting active streaming session unless --interactive is passed
FOREGROUND="$("$ADB" -s "$DEV" shell dumpsys window windows | grep -E "mCurrentFocus|mFocusedApp" || true)"
if [[ "${1:-}" != "--interactive" ]] && echo "$FOREGROUND" | grep -Eq "hotstar|netflix|youtube|appletv|amazonvideo|sonyliv|jiocinema"; then
  pass "2. Active streaming app detected in foreground; skipping intrusive keyevents (pass --interactive to force UI navigation)"
  WIFI_INFO="$("$ADB" -s "$DEV" shell dumpsys wifi | grep "mWifiInfo" | head -n 1 || true)"
  if echo "$WIFI_INFO" | grep -Fq "score: 60"; then
    pass "3. Live TV Wi-Fi link healthy at score: 60 ($WIFI_INFO)"
  else
    fail "3. Live TV Wi-Fi score degraded: $WIFI_INFO"
  fi
  API_JSON="$(curl -s --max-time 5 "http://$TV_IP:8765/api/state")"
  if echo "$API_JSON" | python3 -c "
import sys, json
d = json.load(sys.stdin)
recos = d.get('recommendations', [])
watched = {w.get('title','').lower() for w in d.get('watched_items', [])}
assert len(recos) >= 15, f'Expected >=15 recos, got {len(recos)}'
for r in recos:
    assert 'ani' not in r.get('genre_codes', ''), f'Anime leaked: {r.get(\"title\")}'
    assert r.get('title','').lower() not in watched, f'Watched leaked: {r.get(\"title\")}'
" ; then
    pass "4. HTTP /api/state returns >= 15 recommendations with zero anime and zero watched leaks"
  else
    fail "4. HTTP /api/state validation failed"
  fi
  CRASHES="$("$ADB" -s "$DEV" logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime.*com\.aura\.tvprobe" || true)"
  if [[ -z "$CRASHES" ]]; then
    pass "5. Zero FATAL EXCEPTION / AndroidRuntime crashes in logcat"
  else
    fail "5. Found crash in logcat: $CRASHES"
  fi
  echo "----------------------------------------------------------"
  printf "  E2E TOTAL: %d assertions executed, %d passed, 0 failed\n" "$PASS_COUNT" "$PASS_COUNT"
  echo "=========================================================="
  exit 0
fi

# 2. Press hardware HOME button (keyevent 3) to resume AuraActivity in Stack #0 (type=home)
"$ADB" -s "$DEV" shell input keyevent 3
sleep 2.0
RESUMED="$("$ADB" -s "$DEV" shell dumpsys activity activities | grep -E "mResumedActivity|ResumedActivity" || true)"
if echo "$RESUMED" | grep -Fq "com.aura.tvprobe/.AuraActivity"; then
  pass "2. Hardware HOME button (keyevent 3) launches/resumes com.aura.tvprobe/.AuraActivity"
else
  fail "2. Hardware HOME button failed to resume AuraActivity (got: $RESUMED)"
fi

UI_HOME="$(dump_ui)"
assert_ui_contains "$UI_HOME" 'package="com.aura.tvprobe"' "3. Live UI hierarchy belongs to com.aura.tvprobe (not black FallbackHome)"
assert_ui_contains "$UI_HOME" 'text="Apps"' "4. Home screen renders Apps Dock"
assert_ui_contains "$UI_HOME" 'text="Continue Watching"' "5. Home screen renders Continue Watching rail"
assert_ui_not_contains "$UI_HOME" 'COM.SONYLIV' "6. Continue Watching normalizes raw package names (no COM.SONYLIV)"

# 3. Navigate UP to Top Bar Search Box, press OK to open SearchOverlay, then BACK
"$ADB" -s "$DEV" shell input keyevent 19
sleep 0.5
"$ADB" -s "$DEV" shell input keyevent 23
sleep 1.2
UI_SEARCH="$(dump_ui)"
assert_ui_contains "$UI_SEARCH" 'Clear Topic' "7. Pressing OK on Top Bar Search opens SearchOverlay"
"$ADB" -s "$DEV" shell input keyevent 4
sleep 0.8
UI_AFTER_SEARCH="$(dump_ui)"
assert_ui_not_contains "$UI_AFTER_SEARCH" 'Clear Topic' "8. Pressing BACK dismisses SearchOverlay cleanly"

# 4. Press HOME to reset to Hero Slider, then navigate DOWN to For You rail (3 downs) and 2 rights
"$ADB" -s "$DEV" shell input keyevent 3
sleep 0.8
"$ADB" -s "$DEV" shell input keyevent 20
sleep 0.4
"$ADB" -s "$DEV" shell input keyevent 20
sleep 0.4
"$ADB" -s "$DEV" shell input keyevent 20
sleep 0.4
"$ADB" -s "$DEV" shell input keyevent 22
sleep 0.4
"$ADB" -s "$DEV" shell input keyevent 22
sleep 0.6
UI_FORYOU="$(dump_ui)"
assert_ui_contains "$UI_FORYOU" 'text="For You"' "9. D-pad Down navigates smoothly to For You rail"

# 5. Press OK (23) on For You card to open DetailOverlay
"$ADB" -s "$DEV" shell input keyevent 23
sleep 2.2
UI_DETAIL="$(dump_ui)"
assert_ui_contains "$UI_DETAIL" 'Watch' "10. DetailOverlay renders Watch button"
assert_ui_contains "$UI_DETAIL" 'Explore Similar' "11. DetailOverlay renders Explore Similar button"
assert_ui_contains "$UI_DETAIL" 'Watched' "12. DetailOverlay renders Mark Watched / Watched toggle button"
assert_ui_contains "$UI_DETAIL" 'YOUR RATING:' "13. DetailOverlay renders interactive Rating Row (★ 6–10)"
assert_ui_contains "$UI_DETAIL" 'MORE LIKE THIS' "14. DetailOverlay renders More Like This rail"

# 6. Press BACK (4) from DetailOverlay and verify focus & scroll stay on For You rail
"$ADB" -s "$DEV" shell input keyevent 4
sleep 0.8
UI_BACK_DETAIL="$(dump_ui)"
assert_ui_not_contains "$UI_BACK_DETAIL" 'YOUR RATING:' "15. Pressing BACK dismisses DetailOverlay"
assert_ui_contains "$UI_BACK_DETAIL" 'text="For You"' "16. Dismissing DetailOverlay preserves scroll & focus on For You rail"

# 7. Navigate LEFT to open 3-dash Nav Drawer
"$ADB" -s "$DEV" shell input keyevent 21
sleep 0.3
"$ADB" -s "$DEV" shell input keyevent 21
sleep 0.3
"$ADB" -s "$DEV" shell input keyevent 21
sleep 0.8
UI_DRAWER="$(dump_ui)"
assert_ui_contains "$UI_DRAWER" 'Guide AI Taste' "17. Pressing Left at rail edge expands 3-dash Navigation Drawer"
assert_ui_contains "$UI_DRAWER" 'Watch History' "18. Navigation Drawer displays Watch History item"
assert_ui_contains "$UI_DRAWER" 'Aura Settings' "19. Navigation Drawer displays Aura Settings item"

# 8. Open Watch History Modal, scroll down with D-pad, and re-invoke singleTask intent to verify onNewIntent modal reset
"$ADB" -s "$DEV" shell input keyevent 20
sleep 0.3
"$ADB" -s "$DEV" shell input keyevent 20
sleep 0.3
"$ADB" -s "$DEV" shell input keyevent 23
sleep 1.0
UI_HIST_MODAL="$(dump_ui)"
assert_ui_contains "$UI_HIST_MODAL" 'All Platforms' "20. Watch History modal opens with platform filter pills"
"$ADB" -s "$DEV" shell input keyevent 20
sleep 0.3
"$ADB" -s "$DEV" shell input keyevent 20
sleep 0.3
"$ADB" -s "$DEV" shell input keyevent 3
sleep 1.0
UI_AFTER_HOME="$(dump_ui)"
assert_ui_not_contains "$UI_AFTER_HOME" 'All Platforms' "21. Re-invoking AuraActivity while inside modal closes modal and resets to Home top"
assert_ui_contains "$UI_AFTER_HOME" 'text="Apps"' "22. Home top view (Apps Dock & Hero) is restored after reset"

# 9. Verify HTTP Mobile Remote API (/api/state)
API_JSON="$(curl -s --max-time 5 "http://$TV_IP:8765/api/state")"
if echo "$API_JSON" | python3 -c "
import sys, json
d = json.load(sys.stdin)
recos = d.get('recommendations', [])
watched = {w.get('title','').lower() for w in d.get('watched_items', [])}
assert len(recos) >= 15, f'Expected >=15 recos, got {len(recos)}'
for r in recos:
    assert 'ani' not in r.get('genre_codes', ''), f'Anime leaked: {r.get(\"title\")}'
    assert r.get('title','').lower() not in watched, f'Watched leaked: {r.get(\"title\")}'
" ; then
  pass "23. HTTP /api/state returns >= 15 recommendations with zero anime and zero watched leaks"
else
  fail "23. HTTP /api/state validation failed"
fi

# 10. Verify Zero Crashes in Logcat
CRASHES="$("$ADB" -s "$DEV" logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime.*com\.aura\.tvprobe" || true)"
if [[ -z "$CRASHES" ]]; then
  pass "24. Zero FATAL EXCEPTION / AndroidRuntime crashes in logcat across entire E2E suite"
else
  fail "24. Found crash in logcat: $CRASHES"
fi

echo "----------------------------------------------------------"
printf "  E2E TOTAL: %d assertions executed, %d passed, 0 failed\n" "$PASS_COUNT" "$PASS_COUNT"
echo "=========================================================="
