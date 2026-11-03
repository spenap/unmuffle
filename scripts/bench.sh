#!/bin/bash
# Transcribes each voice message with each installed app and prints the
# time; transcripts go to $BENCH_DIR/results/. The phone slows as it warms
# up, so the apps swap order every run, each transcription first waits
# until the processor (AP) is within 2 °C of where the bench started (at
# most $COOLDOWN seconds, default 180), and each row records the starting
# AP and skin temperatures, Android's thermal status, the fast cores'
# average frequency cap while transcribing (100% = not throttled), and
# whether the phone slept or locked (such rows don't count). The phone
# stays awake while plugged in for the bench; the setting is restored.
# Compare over several runs.
# Measure release builds only.
#
# Usage: scripts/bench.sh RUNS PACKAGE...
#   e.g. scripts/bench.sh 3 com.simonpena.unmuffle com.simonpena.unmuffle.bench
# Needs: one phone on adb (or ANDROID_SERIAL), screen unlocked, each app
# with its model, and voice messages in $BENCH_DIR (default
# ~/Projects/unmuffle-bench; never in the repo).
# A candidate: ./gradlew assembleRelease -Pbench, install it, then
#   scripts/bench.sh --model com.simonpena.unmuffle.bench
# copies the small model from ~/.cache/unmuffle to it.
set -euo pipefail
bench=${BENCH_DIR:-$HOME/Projects/unmuffle-bench}
cooldown=${COOLDOWN:-180}
model=ggml-small-q8_0.bin

# With adbd as root (the Pixel 5), folders it creates belong to root and the
# app can't use them: give the app's folder back to the app.
own() {
    [ "$(adb shell whoami | tr -d '\r')" = root ] || return 0
    uid=$(adb shell pm list packages -U "$1" | sed -n "s/^package:$1 uid://p" | tr -d '\r')
    adb shell chown -R "$uid:ext_data_rw" "/sdcard/Android/data/$1"
}

if [ "${1:-}" = --model ]; then
    dir=/sdcard/Android/data/$2/files/models
    adb shell mkdir -p "$dir"
    adb push "$HOME/.cache/unmuffle/$model" "$dir/" >/dev/null
    own "$2"
    echo "$model copied to $2"
    exit 0
fi

# "AP SKIN STATUS": processor and skin temperatures (°C), thermal status (0 = none)
thermal() {
    adb shell dumpsys thermalservice | tr -d '\r' | awk '
        /Current temperatures from HAL/ { cur = 1 }
        /Current cooling devices/ { cur = 0 }
        cur && /mName=AP,/ { sub(/.*mValue=/, ""); sub(/,.*/, ""); ap = $0 }
        cur && /mName=SKIN,/ { sub(/.*mValue=/, ""); sub(/,.*/, ""); skin = $0 }
        /^Thermal Status:/ { status = $3 }
        END { print ap, skin, status }'
}
# The fast cores' scaling_max_freq / cpuinfo_max_freq, in % (cpu6-7 on the S25)
freq_cap() {
    adb shell 'for c in 6 7; do f=/sys/devices/system/cpu/cpu$c/cpufreq; echo $(cat $f/scaling_max_freq) $(cat $f/cpuinfo_max_freq); done' \
        | tr -d '\r' | awk '{ s += 100 * $1 / $2; n++ } END { print int(s / n) }'
}
# Succeeds if the screen is off or the lock screen is up
asleep() {
    adb shell dumpsys activity activities | grep -q -E 'mSleeping=true|"keyguard"'
}

old_stay_on=$(adb shell settings get global stay_on_while_plugged_in | tr -d '\r')
adb shell settings put global stay_on_while_plugged_in 15  # AC, USB, wireless, dock
trap 'adb shell settings put global stay_on_while_plugged_in "$old_stay_on"' EXIT
trap 'exit 1' INT TERM
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
sleep 1
asleep && { echo "Unlock the phone, then run again."; exit 1; }
cool_to=$(thermal | awk '{ print $1 + 2 }')

runs=${1:?usage: $0 RUNS PACKAGE...}; shift
[ $# -ge 1 ] || { echo "usage: $0 RUNS PACKAGE..."; exit 1; }

# The messages go in each app's own folder, which it reads without any
# permission; the shell can't grant access to MediaStore URIs.
messages=()
for f in "$bench"/*.opus "$bench"/*.ogg "$bench"/*.m4a "$bench"/*.mp3 "$bench"/*.wav; do
    [ -e "$f" ] && messages+=("$f")
done
[ ${#messages[@]} -gt 0 ] || { echo "No voice messages in $bench"; exit 1; }
for pkg in "$@"; do
    adb shell mkdir -p "/sdcard/Android/data/$pkg/files/bench"
    adb push "${messages[@]}" "/sdcard/Android/data/$pkg/files/bench/" >/dev/null
    own "$pkg"
done

out="$bench/results/$(date +%F_%H%M%S)"
mkdir -p "$out"
printf 'run\tpackage\tversion\tmessage\taudio_s\ttranscribe_ms\tap_c\tskin_c\tstatus\tfreq_cap_pct\tslept\n' | tee "$out/times.tsv"
packages=("$@")
for run in $(seq "$runs"); do
    order=("${packages[@]}")
    if [ $((run % 2)) -eq 0 ]; then
        order=(); for ((i = ${#packages[@]} - 1; i >= 0; i--)); do order+=("${packages[i]}"); done
    fi
    for pkg in "${order[@]}"; do
        version=$(adb shell dumpsys package "$pkg" | sed -n 's/.*versionName=//p' | head -1 | tr -d '\r')
        for f in "${messages[@]}"; do
            name=$(basename "$f")
            adb shell am force-stop "$pkg"
            for _ in $(seq "$cooldown"); do
                awk -v ap="$(thermal | cut -d' ' -f1)" -v to="$cool_to" 'BEGIN { exit !(ap <= to) }' && break
                sleep 1
            done
            read -r ap skin status < <(thermal)
            caps=0; samples=0; slept=no
            adb logcat -c
            adb shell am start -a android.intent.action.SEND -t audio/ogg \
                --eu android.intent.extra.STREAM "file:///sdcard/Android/data/$pkg/files/bench/$name" \
                -n "$pkg/com.simonpena.unmuffle.ShareReceiverActivity" >/dev/null
            line=
            for waited in $(seq 300); do
                sleep 1
                caps=$((caps + $(freq_cap))); samples=$((samples + 1))
                asleep && slept=yes
                log=$(adb logcat -d -s WhisperEngine:I TranscriptionVM:I | tr -d '\r')
                line=$(echo "$log" | grep -E 'Transcription complete in|Resampled:' || true)
                echo "$line" | grep -q 'complete in' && break
                if [ "$waited" -ge 20 ] && ! echo "$log" | grep -q 'Starting transcription'; then
                    echo "$pkg $name: transcription didn't start within 20 s"; exit 1
                fi
            done
            ms=$(echo "$line" | sed -n 's/.*complete in \([0-9]*\)ms.*/\1/p')
            secs=$(echo "$line" | sed -n 's/.*(\([0-9.]*\)s).*/\1/p' | head -1)
            [ -n "$ms" ] || { echo "$pkg $name: no result after 300 s"; exit 1; }
            sleep 1
            adb shell uiautomator dump /sdcard/unmuffle-bench-ui.xml >/dev/null
            adb shell cat /sdcard/unmuffle-bench-ui.xml | python3 -I -c '
import sys, xml.etree.ElementTree as ET
ids = [sys.argv[1] + ":id/" + i for i in ("tv_text", "tv_transcript")]  # tv_transcript before v1.6
node = next((n for n in ET.parse(sys.stdin).iter("node") if n.get("resource-id") in ids), None)
print(node.get("text") if node is not None else "")' "$pkg" > "$out/$run.$pkg.${name%.*}.txt"
            [ -s "$out/$run.$pkg.${name%.*}.txt" ] || { echo "$pkg $name: no transcript on screen"; exit 1; }
            printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$run" "$pkg" "$version" "$name" "$secs" "$ms" \
                "$ap" "$skin" "$status" "$((caps / samples))" "$slept" | tee -a "$out/times.tsv"
        done
        adb shell am force-stop "$pkg"
    done
done
adb shell rm -f /sdcard/unmuffle-bench-ui.xml
for pkg in "$@"; do adb shell rm -rf "/sdcard/Android/data/$pkg/files/bench"; done
echo "Results: $out"
