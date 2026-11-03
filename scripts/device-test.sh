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

# Fail fast: one device, unlocked. AwakeRunner keeps it awake during the run;
# the setting is also restored here in case the test process crashes.
adb get-state >/dev/null
old_stay_on=$(adb shell settings get global stay_on_while_plugged_in | tr -d '\r')
# ShareTranscriptTest switches the app to the tiny model and auto-detect; a
# crash skips its reset, so the script resets both too.
reset() {
    adb shell settings put global stay_on_while_plugged_in "$old_stay_on"
    adb shell am force-stop "$APP"
    adb shell run-as "$APP" sed -i -e /pref_model/d -e /pref_language/d shared_prefs/unmuffle_prefs.xml 2>/dev/null || true
}
trap reset EXIT
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
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
# The app must create its own folders: ones adb creates (as shell or root)
# aren't usable by it.
owner() { adb shell stat -c %U "$1" 2>/dev/null | tr -d '\r'; }
if [ -z "$(owner "$DEST")" ] || [ "$(owner "$DEST")" != "$(owner "/sdcard/Android/data/$APP")" ]; then
    adb shell rm -rf "$DEST"
    adb shell am start -W -n "$APP/com.simonpena.unmuffle.MainActivity" >/dev/null
    adb shell am force-stop "$APP"
fi
if [ "$(adb shell stat -c %s "$DEST/$MODEL" 2>/dev/null | tr -d '\r')" != "$(stat -c %s "$CACHE")" ]; then
    adb push "$CACHE" "$DEST/$MODEL" >/dev/null
fi

out=$(timeout 300 adb shell am instrument -w "$APP.test/com.simonpena.unmuffle.AwakeRunner")
echo "$out"
grep -q '^OK (' <<<"$out"
