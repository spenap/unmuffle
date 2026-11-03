#!/usr/bin/env bash
# Instrumented tests on a connected phone (ANDROID_SERIAL picks one), with
# the tiny model pushed to the debug app. Installs instead of using
# connectedDebugAndroidTest, which uninstalls the app and its model after.
set -euo pipefail
cd "$(dirname "$0")/.."

APP=com.simonpena.unmuffle.debug
MODEL=ggml-tiny-q5_1.bin
SHA=818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7
CACHE=~/.cache/unmuffle/$MODEL
DEST=/sdcard/Android/data/$APP/files/models

# Fail fast: one device, awake and unlocked. The screen stays on while
# plugged in (any charger) until the script ends.
adb get-state >/dev/null
old_stay_on=$(adb shell settings get global stay_on_while_plugged_in | tr -d '\r')
adb shell settings put global stay_on_while_plugged_in 15  # AC, USB, wireless, dock
trap 'adb shell settings put global stay_on_while_plugged_in "$old_stay_on"' EXIT
adb shell input keyevent KEYCODE_WAKEUP
sleep 1
if adb shell dumpsys window | grep -Eq 'mDreamingLockscreen=true|isKeyguardShowing=true|mShowingLockscreen=true'; then
    echo "Phone is locked"; exit 1
fi

if [ ! -f "$CACHE" ]; then
    mkdir -p "$(dirname "$CACHE")"
    curl -sSLf -o "$CACHE" "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$MODEL"
fi
echo "$SHA  $CACHE" | sha256sum -c --quiet

./gradlew -q installDebug installDebugAndroidTest
adb shell mkdir -p "$DEST"
if [ "$(adb shell stat -c %s "$DEST/$MODEL" 2>/dev/null | tr -d '\r')" != "$(stat -c %s "$CACHE")" ]; then
    adb push "$CACHE" "$DEST/$MODEL" >/dev/null
fi

out=$(timeout 300 adb shell am instrument -w "$APP.test/androidx.test.runner.AndroidJUnitRunner")
echo "$out"
grep -q '^OK (' <<<"$out"
