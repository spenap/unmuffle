#!/bin/bash
# Checks that a release APK is built from its source: rebuilds the tag from
# a fresh clone and compares it with the APK, signature aside
# (apksigcopier, as F-Droid does).
# Usage: scripts/repro-check.sh <tag or commit> [apk] (default: the tag's
# GitHub Release APK)
# Needs: `gh` logged in, JDK 17, the Android SDK; installs apksigcopier
# into ~/.cache/unmuffle/apksigcopier the first time.
set -euo pipefail
ref=${1:?Usage: $0 <tag or commit> [apk]}
sdk=${ANDROID_HOME:-$HOME/Android/Sdk}
export ANDROID_HOME=$sdk
export PATH="$(dirname "$(ls -d "$sdk"/build-tools/*/apksigner | tail -1)"):$PATH"
venv=$HOME/.cache/unmuffle/apksigcopier
[ -x "$venv/bin/apksigcopier" ] || { python3 -m venv "$venv"; "$venv/bin/pip" install -q 'apksigcopier==1.1.1'; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
if [ -n "${2:-}" ]; then
    apk=$(realpath "$2")
else
    gh release download "$ref" -R spenap/unmuffle -p '*.apk' -D "$work/release"
    apk=$(ls "$work"/release/*.apk)
fi

gh repo clone spenap/unmuffle "$work/src" -- -q
cd "$work/src"
git -c advice.detachedHead=false checkout -q "$ref"
git submodule -q update --init
./gradlew assembleRelease -q --no-daemon --console=plain
"$venv/bin/apksigcopier" compare "$apk" app/build/outputs/apk/release/app-release.apk \
    && echo "$(basename "$apk") is built from $ref ($(git rev-parse --short HEAD))" \
    || { echo "$(basename "$apk") differs from a build of $ref"; exit 1; }
