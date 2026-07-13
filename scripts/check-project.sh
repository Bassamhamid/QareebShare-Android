#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

required=(
  settings.gradle
  build.gradle
  app/build.gradle
  app/src/main/AndroidManifest.xml
  app/src/main/java/com/bassam/qareebshare/MainActivity.java
  app/src/main/res/layout/activity_home.xml
  .github/workflows/android.yml
)

for path in "${required[@]}"; do
  if [[ ! -f "$path" ]]; then
    echo "MISSING: $path" >&2
    exit 1
  fi
done

if grep -R --line-number --include='build.gradle' -E '^[[:space:]]*(implementation|api|runtimeOnly|debugImplementation|releaseImplementation)[[:space:]]' .; then
  echo "External runtime dependency declaration found." >&2
  exit 1
fi

VERSION="$(sed -n "s/.*versionName = '\([^']*\)'.*/\1/p" app/build.gradle | head -1)"
echo "========================================"
echo "QAREEB SHARE PROJECT CHECK: OK"
echo "Project: $ROOT"
echo "Version: ${VERSION:-unknown}"
echo "Runtime libraries: none"
echo "minSdk: 23 | targetSdk: 37"
echo "========================================"
