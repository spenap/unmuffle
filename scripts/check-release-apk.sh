#!/bin/sh
# Fails if the release APK asks for more than the INTERNET permission,
# allows backup, or has native libraries that 16 KB-page devices refuse.
# Usage: scripts/check-release-apk.sh [apk]
set -eu
cd "$(dirname "$0")/.."
apk=${1:-app/build/outputs/apk/release/app-release.apk}
sdk=${ANDROID_HOME:-$HOME/Android/Sdk}
aapt=$(ls -d "$sdk"/build-tools/*/aapt | tail -1)
fail=0
# Network only for the model download; nothing backed up
perms=$("$aapt" dump permissions "$apk" | sed -n "s/^uses-permission: name='\(.*\)'/\1/p" | grep -v '\.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION$')
[ "$perms" = android.permission.INTERNET ] || { echo "$apk: permissions beyond INTERNET:" $perms; fail=1; }
"$aapt" dump xmltree "$apk" AndroidManifest.xml | grep -q 'allowBackup.*=(type 0x12)0x0' \
    || { echo "$apk allows backup"; fail=1; }
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
