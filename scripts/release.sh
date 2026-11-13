#!/bin/sh
# Tag main, build the APK signed with the release key, and publish it as a
# GitHub Release (installed on the S25 through Obtainium).
# The version is the next one git-cliff computes from the Conventional
# Commits since the last tag (cliff.toml); vX.Y.Z overrides it.
# Needs: a clean main equal to origin/main, CI green on it, `gh` and
# `git-cliff` installed, and the key in ~/.config/unmuffle (release.jks,
# release.pass).
# Usage: scripts/release.sh [vX.Y.Z]
set -eu
cd "$(dirname "$0")/.."
v=${1:-}
key=${UNMUFFLE_KEY_DIR:-$HOME/.config/unmuffle}

if [ -z "$v" ]; then
    command -v git-cliff >/dev/null || { echo "git-cliff not found"; exit 1; }
    v=$(git-cliff --bumped-version 2>/dev/null)
    git rev-parse -q --verify "refs/tags/$v" >/dev/null && { echo "Nothing to release since $v"; exit 1; }
    echo "Releasing $v"
fi
echo "$v" | grep -Eq '^v[0-9]+\.[0-9]+\.[0-9]+$' || { echo "Usage: $0 [vX.Y.Z]"; exit 1; }
[ -r "$key/release.jks" ] && [ -r "$key/release.pass" ] || { echo "No release key in $key"; exit 1; }
[ "$(git branch --show-current)" = main ] || { echo "Not on main"; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "Uncommitted changes"; exit 1; }
git fetch -q origin main
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || { echo "main differs from origin/main"; exit 1; }
git rev-parse -q --verify "refs/tags/$v" >/dev/null && { echo "$v already exists"; exit 1; }
ci=$(gh run list --branch main --limit 1 --json status,conclusion --jq '.[0].status + " " + .[0].conclusion')
[ "$ci" = "completed success" ] || { echo "Latest CI run on main: $ci"; exit 1; }

git tag -a "$v" -m "$v"
trap 'git tag -d "$v" >/dev/null' EXIT # until the tag is pushed
UNMUFFLE_KEYSTORE="$key/release.jks" UNMUFFLE_KEYSTORE_PASSWORD="$(cat "$key/release.pass")" \
    ./gradlew assembleRelease -q --console=plain
apk=app/build/outputs/apk/release/unmuffle-$v.apk
cp app/build/outputs/apk/release/app-release.apk "$apk"
apksigner=$(ls -d "$HOME"/Android/Sdk/build-tools/*/apksigner | tail -1)
# The certificate predates the rename.
"$apksigner" verify --print-certs "$apk" | grep -q "O=TransChat" || { echo "$apk isn't signed with the release key"; exit 1; }

git push -q origin "$v"
trap - EXIT
gh release create "$v" "$apk" --title "$v" --generate-notes
