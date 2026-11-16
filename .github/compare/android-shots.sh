#!/usr/bin/env bash
# Installs the x86_64 debug APK on the running emulator and walks the Home with the D-pad,
# saving a screenshot after each step into shots/android.
set -u
out=shots/android
mkdir -p "$out"
apk=$(ls app/build/outputs/apk/full/debug/*x86_64*.apk | head -1)
echo "Installing $apk"
adb install -r -g "$apk"
pkg=$(adb shell pm list packages | tr -d '\r' | sed -n 's/^package://p' | grep -m1 '^com\.dexhub\.tv')
echo "Package $pkg"
adb shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || \
  adb shell monkey -p "$pkg" 1 >/dev/null 2>&1

shot() { sleep "$2"; adb exec-out screencap -p > "$out/$1.png"; }
key() { adb shell input keyevent "$1"; }

shot 01-start 40
# First-run screens (profile, layout, setup) take OK presses; record each.
for i in 1 2 3 4; do key KEYCODE_DPAD_CENTER; shot "02-firstrun-$i" 8; done
key KEYCODE_BACK; shot 03-after-back 6
shot 04-home 20
key KEYCODE_DPAD_UP; shot 05-bar 4
key KEYCODE_DPAD_RIGHT; shot 06-bar-movies 6
key KEYCODE_DPAD_RIGHT; shot 07-bar-series 6
key KEYCODE_DPAD_DOWN; shot 08-content 6
key KEYCODE_DPAD_DOWN; shot 09-row 6
adb logcat -d -t 400 > "$out/logcat.txt" 2>/dev/null || true
exit 0
