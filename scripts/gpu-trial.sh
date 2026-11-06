#!/bin/bash
# One share of one message to PKG, watched for GPU hangs; prints whisper/ggml
# logs (no transcript text) and the outcome. Hard limit LIMIT s (default 240).
# Usage: scripts/gpu-trial.sh PKG MESSAGE_FILE  (spike: GPU builds, docs/measurements.md)
set -uo pipefail
pkg=$1; f=$2; name=$(basename "$f"); limit=${LIMIT:-240}
old=$(adb shell settings get global stay_on_while_plugged_in | tr -d '\r')
cleanup() {
    adb shell am force-stop "$pkg"
    adb shell rm -f "/sdcard/Android/data/$pkg/files/bench-$name"
    adb shell settings put global stay_on_while_plugged_in "$old"
}
trap cleanup EXIT
adb shell input keyevent KEYCODE_WAKEUP
adb shell dumpsys window | grep -q 'isKeyguardShowing=true' && { echo "OUTCOME locked"; exit 1; }
adb shell settings put global stay_on_while_plugged_in 15
adb push "$f" "/sdcard/Android/data/$pkg/files/bench-$name" >/dev/null
adb shell am force-stop "$pkg"
adb logcat -b all -c
start=$(date +%s)
adb shell am start -a android.intent.action.SEND -t audio/ogg \
    --eu android.intent.extra.STREAM "file:///sdcard/Android/data/$pkg/files/bench-$name" \
    -n "$pkg/com.simonpena.unmuffle.ShareReceiverActivity" >/dev/null
outcome=timeout
while [ $(( $(date +%s) - start )) -lt "$limit" ]; do
    sleep 1
    hang=$(adb logcat -b kernel -b main -b system -b crash -d 2>/dev/null | tr -d '\r' \
        | grep -iE 'kgsl.*(deadlock|fault|hang)|gpu fault|GPU hang|ANR in com.android.systemui' | head -3)
    if [ -n "$hang" ]; then adb shell am force-stop "$pkg"; outcome="GPU-HANG"; echo "$hang"; break; fi
    log=$(adb logcat -d -s whisper:* WhisperJNI:* Whisper:* Transcription:* AndroidRuntime:E libc:F DEBUG:F | tr -d '\r')
    if echo "$log" | grep -q 'Transcribed in'; then outcome=done; break; fi
    if echo "$log" | grep -qE 'FATAL|Fatal signal'; then outcome=crash; break; fi
done
[ "$outcome" = timeout ] && adb shell am force-stop "$pkg"
echo "$log" | grep -vE 'whisper_model_load:  |n_[a-z_]+ +=|Raw|segment' | cut -c 19- | grep -v '^ *$' | head -120
echo "OUTCOME $outcome after $(( $(date +%s) - start )) s"
