#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

fail() {
  echo "خطأ: $1" >&2
  exit 1
}

required=(
  "app/src/main/java/com/bassam/qareebshare/WifiDirectController.java"
  "app/src/main/java/com/bassam/qareebshare/LocalHandshake.java"
  "app/src/main/java/com/bassam/qareebshare/NearbyPermissionManager.java"
  "app/src/main/java/com/bassam/qareebshare/PeerDevice.java"
  "app/src/main/java/com/bassam/qareebshare/PeersAdapter.java"
  "app/src/main/res/layout/row_peer.xml"
  "BATCH_02.md"
)

for file in "${required[@]}"; do
  [[ -f "$file" ]] || fail "الملف مفقود: $file"
done

grep -q "versionName = '0.2.0'" app/build.gradle || fail "إصدار الدفعة الثانية غير مضبوط"
grep -q "minSdk = 23" app/build.gradle || fail "minSdk تغير"
grep -q "targetSdk = 37" app/build.gradle || fail "targetSdk تغير"
grep -q "discoverPeers" app/src/main/java/com/bassam/qareebshare/WifiDirectController.java || fail "اكتشاف الأجهزة غير موجود"
grep -q "createGroup" app/src/main/java/com/bassam/qareebshare/WifiDirectController.java || fail "وضع الاستقبال غير موجود"
grep -q "ServerSocket" app/src/main/java/com/bassam/qareebshare/LocalHandshake.java || fail "خادم TCP غير موجود"
grep -q "new Socket" app/src/main/java/com/bassam/qareebshare/LocalHandshake.java || fail "عميل TCP غير موجود"
grep -q "workflow_dispatch" .github/workflows/android.yml || fail "التشغيل اليدوي للبناء غير موجود"
if grep -Eq '^[[:space:]]*(push|pull_request):' .github/workflows/android.yml; then
  fail "البناء ما زال يعمل تلقائياً عند الدفع"
fi
if grep -Eq '^[[:space:]]*(implementation|api|compileOnly|runtimeOnly|debugImplementation|releaseImplementation)[[:space:]]' app/build.gradle; then
  fail "تم العثور على مكتبة خارجية داخل التطبيق"
fi

if command -v python >/dev/null 2>&1; then
python - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
root = Path('.')
for path in (root / 'app/src/main/res').rglob('*.xml'):
    ET.parse(path)
print('XML: OK')
PY
else
  echo 'XML: skipped (Python is not installed)'
fi

echo "========================================"
echo "QAREEB SHARE BATCH 2 CHECK: OK"
echo "Wi-Fi Direct discovery: present"
echo "Local TCP handshake: present"
echo "Build trigger: manual only"
echo "No APK was built"
echo "========================================"
