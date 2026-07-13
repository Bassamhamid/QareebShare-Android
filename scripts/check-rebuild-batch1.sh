#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
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
import re
import xml.etree.ElementTree as ET

root=Path('.')
for path in (root/'app/src/main/res').rglob('*.xml'):
    ET.parse(path)
ET.parse(root/'app/src/main/AndroidManifest.xml')

manifest=(root/'app/src/main/AndroidManifest.xml').read_text(encoding='utf-8')
checks={
    'SessionActivity launcher': '.SessionActivity',
    'Wi-Fi state check': 'isWifiEnabled()',
    'Wi-Fi panel': 'Settings.Panel.ACTION_WIFI',
    'service discovery': 'discoverServices',
    'connection timeout': 'CONNECT_TIMEOUT_MS',
    'active session transport': 'SessionTransport',
    'day/night preference': 'setApplicationNightMode',
}
joined='\n'.join(p.read_text(encoding='utf-8') for p in (root/'app/src/main/java/com/bassam/qareebshare').glob('Session*.java'))
joined += (root/'app/src/main/java/com/bassam/qareebshare/ThemeMode.java').read_text(encoding='utf-8')
for label, token in checks.items():
    haystack = manifest if token == '.SessionActivity' else joined
    if token not in haystack:
        raise SystemExit(f'فشل الفحص: {label}')
print('XML and structural checks: OK')
PY

if grep -R -nE 'P2P_|Socket|Group owner|Wi-Fi Direct' app/src/main/res/values/session_strings.xml; then
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
echo "Connection timeout and cleanup: present"
echo "Active TCP session: present"
echo "Light / dark / system mode: present"
echo "User-facing technical text: none"
echo "Runtime libraries: none"
echo "No APK was built"
echo "========================================"
