#!/bin/bash
# Scans an APK for trackers with Exodus Privacy's scanner (exodus-core, the
# library behind reports.exodus-privacy.eu.org) and prints its report.
# Fails if it finds any tracker, or if the tracker signatures didn't load.
# Usage: scripts/exodus-scan.sh [apk] (default: the release build)
# Needs: python3, the Android SDK's dexdump; downloads the scanner into
# ~/.cache/unmuffle/exodus the first time, and the signatures each run.
set -euo pipefail
cd "$(dirname "$0")/.."
apk=$(realpath "${1:-app/build/outputs/apk/release/app-release.apk}")
sdk=${ANDROID_HOME:-$HOME/Android/Sdk}
export PATH="$(dirname "$(ls -d "$sdk"/build-tools/*/dexdump | tail -1)"):$PATH"
venv=$HOME/.cache/unmuffle/exodus
if [ ! -x "$venv/bin/python" ]; then
    python3 -m venv "$venv"
    # exodus-core 1.3.14 pins versions without wheels for current Python;
    # its other dependencies install unpinned.
    "$venv/bin/pip" install -q 'androguard==4.1.1' cryptography dhash jellyfish Pillow 'requests>=2.26,<2.33' six traitlets
    "$venv/bin/pip" install -q --no-deps exodus-core==1.3.14
fi
cd "$venv"  # never run Python from the repo or the APK's folder
"$venv/bin/python" -I - "$apk" <<'PY' 2> >(grep -v -E '\| (INFO|WARNING) ' >&2)
import sys
from exodus_core.analysis.static_analysis import StaticAnalysis
a = StaticAnalysis(sys.argv[1])
a.load_trackers_signatures()
if len(a.signatures) < 100:
    sys.exit(f"Only {len(a.signatures)} tracker signatures loaded")
trackers = a.detect_trackers()
print(f"{a.get_package()} {a.get_version()} · SHA-256 {a.get_sha256()}")
print(f"Permissions: {', '.join(a.get_permissions())}")
print(f"{len(a.signatures)} tracker signatures, {len(a.get_embedded_classes())} classes: "
      + (", ".join(t.name for t in trackers) or "no trackers"))
sys.exit(1 if trackers else 0)
PY
