#!/bin/bash
# Transcribes each voice message with each installed app and prints the
# time; transcripts go to $BENCH_DIR/results/. The phone slows as it warms
# up, so the apps swap order every run and each transcription waits
# $COOLDOWN seconds (default 30) first. Compare over several runs.
# Measure release builds only: debug builds compile whisper.cpp unoptimised.
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
cooldown=${COOLDOWN:-30}
model=ggml-small-q5_1.bin

if [ "${1:-}" = --model ]; then
    dir=/sdcard/Android/data/$2/files/models
    adb shell mkdir -p "$dir"
    adb push "$HOME/.cache/unmuffle/$model" "$dir/" >/dev/null
    echo "$model copied to $2"
    exit 0
fi
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
done

out="$bench/results/$(date +%F_%H%M%S)"
mkdir -p "$out"
printf 'run\tpackage\tversion\tmessage\taudio_s\ttranscribe_ms\n' | tee "$out/times.tsv"
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
            sleep "$cooldown"
            adb logcat -c
            adb shell am start -a android.intent.action.SEND -t audio/ogg \
                --eu android.intent.extra.STREAM "file:///sdcard/Android/data/$pkg/files/bench/$name" \
                -n "$pkg/com.simonpena.unmuffle.ShareReceiverActivity" >/dev/null
            line=
            for _ in $(seq 300); do
                sleep 1
                line=$(adb logcat -d -s WhisperEngine:I TranscriptionVM:I | grep -E 'Transcription complete in|Resampled:' | tr -d '\r' || true)
                echo "$line" | grep -q 'complete in' && break
            done
            ms=$(echo "$line" | sed -n 's/.*complete in \([0-9]*\)ms.*/\1/p')
            secs=$(echo "$line" | sed -n 's/.*(\([0-9.]*\)s).*/\1/p' | head -1)
            [ -n "$ms" ] || { echo "$pkg $name: no result after 300 s"; exit 1; }
            sleep 1
            adb shell uiautomator dump /sdcard/unmuffle-bench-ui.xml >/dev/null
            adb shell cat /sdcard/unmuffle-bench-ui.xml | python3 -I -c '
import sys, xml.etree.ElementTree as ET
node = ET.parse(sys.stdin).find(".//node[@resource-id=\"" + sys.argv[1] + ":id/tv_transcript\"]")
print(node.get("text") if node is not None else "")' "$pkg" > "$out/$run.$pkg.${name%.*}.txt"
            printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$run" "$pkg" "$version" "$name" "$secs" "$ms" | tee -a "$out/times.tsv"
        done
        adb shell am force-stop "$pkg"
    done
done
adb shell rm -f /sdcard/unmuffle-bench-ui.xml
for pkg in "$@"; do adb shell rm -rf "/sdcard/Android/data/$pkg/files/bench"; done
echo "Results: $out"
