#!/bin/sh
# Fails if the release APK carries debug-only code or assets.
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
exit $fail
