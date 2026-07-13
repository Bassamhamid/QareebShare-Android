#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

required=(
  app/src/main/java/com/bassam/qareebshare/SessionActivity.java
  app/src/main/java/com/bassam/qareebshare/SessionController.java
  app/src/main/java/com/bassam/qareebshare/SessionTransport.java
  app/src/main/java/com/bassam/qareebshare/SessionPeerAdapter.java
  app/src/main/java/com/bassam/qareebshare/ThemeMode.java
  app/src/main/res/layout/activity_session.xml
  app/src/main/res/layout/row_session_peer.xml
  app/src/main/res/values/session_strings.xml
  app/src/main/res/values/session_colors.xml
  app/src/main/res/values-night/session_colors.xml
  REBUILD_BATCH_01.md
)

for file in "${required[@]}"; do
  test -f "$file" || { echo "ملف مفقود: $file"; exit 1; }
done

python - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path('.')
for path in (root / 'app/src/main/res').rglob('*.xml'):
    ET.parse(path)
ET.parse(root / 'app/src/main/AndroidManifest.xml')

manifest = (root / 'app/src/main/AndroidManifest.xml').read_text(encoding='utf-8')
controller = (root / 'app/src/main/java/com/bassam/qareebshare/SessionController.java').read_text(encoding='utf-8')
activity = (root / 'app/src/main/java/com/bassam/qareebshare/SessionActivity.java').read_text(encoding='utf-8')
transport = (root / 'app/src/main/java/com/bassam/qareebshare/SessionTransport.java').read_text(encoding='utf-8')
theme = (root / 'app/src/main/java/com/bassam/qareebshare/ThemeMode.java').read_text(encoding='utf-8')

checks = {
    'SessionActivity launcher': '.SessionActivity',
    'Wi-Fi state check': 'isWifiEnabled()',
    'Wi-Fi settings panel': 'Settings.Panel.ACTION_WIFI',
    'service discovery': 'discoverServices',
    'asynchronous P2P cleanup': 'prepareEnvironment',
    'cleanup disconnect guard': 'cleanupInProgress',
    'connection information polling': 'pollConnectionInfo',
    'connection timeout': 'CONNECT_TIMEOUT_MS',
    'active session heartbeat': 'HEARTBEAT_INTERVAL_MS',
    'light/dark/system preference': 'setApplicationNightMode',
}
for label, token in checks.items():
    haystack = manifest if token == '.SessionActivity' else '\n'.join((controller, activity, transport, theme))
    if token not in haystack:
        raise SystemExit(f'فشل الفحص: {label}')

print('XML and reviewed structural checks: OK')
PY

if grep -R -nE 'P2P_|Group owner|Socket failed|Wi-Fi Direct|error code' \
  app/src/main/res/values/session_strings.xml; then
  echo "خطأ: يوجد نص تقني في واجهة المستخدم"
  exit 1
fi

if grep -qE 'implementation|api |compileOnly|runtimeOnly' app/build.gradle; then
  echo "خطأ: تم العثور على اعتماد تشغيل خارجي"
  exit 1
fi

echo "========================================"
echo "QAREEB SHARE REBUILD BATCH 1 CHECK: OK"
echo "Wi-Fi off handling: present"
echo "Qareeb Share-only discovery: present"
echo "Asynchronous stale-group cleanup: present"
echo "Connection polling and timeout: present"
echo "Active TCP session: present"
echo "Light / dark / system mode: present"
echo "User-facing technical text: none"
echo "Runtime libraries: none"
echo "No APK was built"
echo "========================================"
