#!/usr/bin/env bash
# Installs the x86_64 debug APK on the running emulator and walks the Home with the D-pad,
# saving a screenshot after each step into shots/android.
set -u
out=shots/android
mkdir -p "$out"
apk=$(ls app/build/outputs/apk/full/debug/*x86_64*.apk | head -1)
echo "Installing $apk"
timeout 180 adb install -r -g "$apk"
adb logcat -c || true
(adb logcat -v time > "$out/logcat-live.txt" 2>&1 &)
pkg=$(adb shell pm list packages | tr -d '\r' | sed -n 's/^package://p' | grep -m1 '^com\.dexhub\.tv')
echo "Package $pkg"
adb shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || \
  adb shell monkey -p "$pkg" 1 >/dev/null 2>&1

shot() { sleep "$2"; timeout 30 adb exec-out screencap -p > "$out/$1.png" || echo "shot $1 failed"; }
key() { timeout 20 adb shell input keyevent "$1" || echo "key $1 failed"; }

shot 01-start 40
# Sign-in page: "Continue without an account" is right of the focused "Refresh code".
key KEYCODE_DPAD_RIGHT; shot 02-signin-right 3
key KEYCODE_DPAD_CENTER; shot 03-after-signin 12
# Whatever first-run pages follow (profile, layout): accept each, recording it.
shot 04-firstrun 10
shot 05-home 20
key KEYCODE_DPAD_UP; key KEYCODE_DPAD_UP; shot 06-bar 4
key KEYCODE_DPAD_RIGHT; shot 07-bar-next 6
key KEYCODE_DPAD_RIGHT; shot 08-bar-next2 6
key KEYCODE_DPAD_DOWN; shot 09-content 6
key KEYCODE_DPAD_DOWN; shot 10-row 6
key KEYCODE_DPAD_DOWN; shot 11-row2 6
timeout 30 adb logcat -d -t 600 > "$out/logcat.txt" 2>/dev/null || true
exit 0
