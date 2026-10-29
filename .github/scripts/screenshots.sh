#!/usr/bin/env bash
# Runs inside the emulator: installs the debug APK, seeds demo data, captures screenshots.
set -euxo pipefail

PKG=app.bcalm
ACTIVITY=com.example.anxietytracker.MainActivity
APK=app/build/outputs/apk/sideload/debug/app-sideload-debug.apk
OUT=docs/screenshots
WORK=$(mktemp -d)
mkdir -p "$OUT"

# Let the freshly booted emulator settle, and suppress system "isn't responding" dialogs
adb shell settings put global hide_error_dialogs 1 || true
for _ in $(seq 1 30); do
  [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ] && break; sleep 2
done
sleep 20
adb shell input keyevent KEYCODE_HOME || true
adb install -r "$APK"
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
adb shell am force-stop "$PKG"

# Seed demo data into the app's private storage (debug build => run-as works)
TZ_RAW=$(adb shell date +%z | tr -d '\r')            # e.g. +0000, -0700
SIGN=${TZ_RAW:0:1}; HH=${TZ_RAW:1:2}; MM=${TZ_RAW:3:2}
TZ_MIN=$(( 10#$HH * 60 + 10#$MM )); [ "$SIGN" = "-" ] && TZ_MIN=$(( -TZ_MIN ))
python3 .github/scripts/seed_demo.py "$WORK" "$TZ_MIN"

adb push "$WORK/entries.txt" /data/local/tmp/entries.txt
adb push "$WORK/prompts.txt" /data/local/tmp/prompts.txt
adb shell "run-as $PKG sh -c 'mkdir -p files shared_prefs && cp /data/local/tmp/entries.txt files/entries.txt && cp /data/local/tmp/prompts.txt files/prompts.txt'"

# Prefs (draft.xml) are rewritten before every launch to pick the chart view / mode: set_prefs VIEW MODELED
# (VIEW 0 = time of day, 1 = since waking, 2 = over time). The intro is pre-marked as seen in the seed.
set_prefs() {
  sed -e "s|name=\"chartView3\" value=\"[0-9]*\"|name=\"chartView3\" value=\"$1\"|" \
      -e "s|name=\"chartModeled\" value=\"[a-z]*\"|name=\"chartModeled\" value=\"$2\"|" \
      "$WORK/draft.xml" > "$WORK/draft_now.xml"
  adb push "$WORK/draft_now.xml" /data/local/tmp/draft.xml
  adb shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cp /data/local/tmp/draft.xml shared_prefs/draft.xml'"
}
set_prefs 0 true
adb shell "run-as $PKG ls -l files shared_prefs"

# Clean status bar
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1000
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4
adb shell am broadcast -a com.android.systemui.demo -e command network -e mobile show -e datatype none -e level 4
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false

# capture NAME TAB VIEW MODELED SWIPES: launch on TAB with the chart prefs, optionally scroll SWIPES times, screenshot
capture() {
  local name=$1 tab=$2 view=$3 modeled=$4 swipes=${5:-0}
  adb shell am force-stop "$PKG"
  set_prefs "$view" "$modeled"
  adb shell am start -W -n "$PKG/$ACTIVITY" --ei tab "$tab"
  sleep 5
  # Dismiss any ANR/system dialog that popped up (keep the app in front)
  for _ in 1 2 3; do
    if adb shell dumpsys window | grep -qiE "Application Not Responding|isn't responding"; then
      adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS || true
      adb shell input keyevent KEYCODE_BACK || true
      sleep 2
      adb shell am force-stop "$PKG"
      adb shell am start -W -n "$PKG/$ACTIVITY" --ei tab "$tab"
      sleep 4
    fi
  done
  for _ in $(seq 1 "$swipes"); do
    adb shell input swipe 540 1900 540 900 900   # slow drag: ~1000 px, little fling
    sleep 2
  done
  sleep 2
  adb exec-out screencap -p > "$OUT/$name.png"
}

rm -f "$OUT"/*.png
capture log 0 0 true
capture chart 1 0 true
capture chart-overtime 1 2 true
capture chart-cards 1 0 true 2
capture history 2 0 true
capture settings 3 0 true

adb shell am broadcast -a com.android.systemui.demo -e command exit || true
ls -l "$OUT"
