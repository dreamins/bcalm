#!/usr/bin/env bash
# Runs inside the emulator: installs the debug APK, seeds demo data, captures screenshots.
set -euxo pipefail

PKG=com.example.anxietytracker
APK=app/build/outputs/apk/debug/app-debug.apk
OUT=docs/screenshots
WORK=$(mktemp -d)
mkdir -p "$OUT"

adb install -r "$APK"
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
adb shell am force-stop "$PKG"

# Seed demo data into the app's private storage (debug build => run-as works)
TZ_RAW=$(adb shell date +%z | tr -d '\r')            # e.g. +0000, -0700
SIGN=${TZ_RAW:0:1}; HH=${TZ_RAW:1:2}; MM=${TZ_RAW:3:2}
TZ_MIN=$(( 10#$HH * 60 + 10#$MM )); [ "$SIGN" = "-" ] && TZ_MIN=$(( -TZ_MIN ))
python3 .github/scripts/seed_demo.py "$WORK" "$TZ_MIN"

adb push "$WORK/entries.txt" /data/local/tmp/entries.txt
adb push "$WORK/draft.xml" /data/local/tmp/draft.xml
adb shell "run-as $PKG sh -c 'mkdir -p files shared_prefs && cp /data/local/tmp/entries.txt files/entries.txt && cp /data/local/tmp/draft.xml shared_prefs/draft.xml'"
adb shell "run-as $PKG ls -l files shared_prefs"

# Clean status bar
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1000
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4
adb shell am broadcast -a com.android.systemui.demo -e command network -e mobile show -e datatype none -e level 4
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false

NAMES=(log chart history settings)
for i in 0 1 2 3; do
  adb shell am force-stop "$PKG"
  adb shell am start -W -n "$PKG/.MainActivity" --ei tab "$i"
  sleep 4
  adb exec-out screencap -p > "$OUT/${NAMES[$i]}.png"
done

adb shell am broadcast -a com.android.systemui.demo -e command exit || true
ls -l "$OUT"
