#!/bin/sh
# Fails if the release APK carries debug-only code or assets, or native
# libraries that 16 KB-page devices refuse.
# Usage: scripts/check-release-apk.sh [apk]
set -eu
cd "$(dirname "$0")/.."
apk=${1:-app/build/outputs/apk/release/app-release.apk}
sdk=${ANDROID_HOME:-$HOME/Android/Sdk}
aapt=$(ls -d "$sdk"/build-tools/*/aapt | tail -1)
fail=0
for f in assets/jfk.wav; do
    unzip -l "$apk" | grep -q " $f\$" && { echo "$apk contains $f"; fail=1; }
done
"$aapt" dump xmltree "$apk" AndroidManifest.xml | grep -q SmokeTestActivity \
    && { echo "$apk declares SmokeTestActivity"; fail=1; }
# Android 15+ devices with 16 KB pages need every native library aligned to
# 16 KB, in the ELF and in the zip.
readelf=$(ls -d "$sdk"/ndk/*/toolchains/llvm/prebuilt/*/bin/llvm-readelf | tail -1)
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
unzip -q "$apk" 'lib/*' -d "$tmp"
for so in "$tmp"/lib/*/*.so; do
    for align in $("$readelf" -lW "$so" | awk '$1 == "LOAD" { print $NF }'); do
        [ $((align)) -ge 16384 ] || { echo "$apk: ${so#$tmp/} isn't aligned to 16 KB"; fail=1; break; }
    done
done
zipalign=$(ls -d "$sdk"/build-tools/*/zipalign | tail -1)
"$zipalign" -c -P 16 4 "$apk" || { echo "$apk: native libraries aren't zip-aligned to 16 KB"; fail=1; }
exit $fail
