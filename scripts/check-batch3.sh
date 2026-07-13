#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

fail() {
  echo "خطأ: $1" >&2
  exit 1
}

required=(
  "BATCH_03.md"
  "app/src/main/java/com/bassam/qareebshare/TransferEngine.java"
  "app/src/main/java/com/bassam/qareebshare/TransferSourceResolver.java"
  "app/src/main/java/com/bassam/qareebshare/ReceivedFileStore.java"
  "app/src/main/java/com/bassam/qareebshare/TransferService.java"
  "app/src/main/java/com/bassam/qareebshare/StoragePermissionManager.java"
  "app/src/main/res/drawable/ic_notification.xml"
)

for file in "${required[@]}"; do
  [[ -f "$file" ]] || fail "الملف مفقود: $file"
done

grep -q "versionName = '0.3.0'" app/build.gradle || fail "إصدار الدفعة الثالثة غير مضبوط"
grep -q "versionCode = 3" app/build.gradle || fail "versionCode غير مضبوط"
grep -q "MessageDigest.getInstance(\"SHA-256\")" app/src/main/java/com/bassam/qareebshare/TransferEngine.java || fail "التحقق SHA-256 غير موجود"
grep -q "BUFFER_SIZE = 256 \* 1024" app/src/main/java/com/bassam/qareebshare/TransferEngine.java || fail "حجم Buffer غير مضبوط"
grep -q "COMMAND_OFFER" app/src/main/java/com/bassam/qareebshare/TransferEngine.java || fail "وصف الدفعة غير موجود"
grep -q "COMMAND_ACK" app/src/main/java/com/bassam/qareebshare/TransferEngine.java || fail "تأكيد العناصر غير موجود"
grep -q "MediaStore.Downloads" app/src/main/java/com/bassam/qareebshare/ReceivedFileStore.java || fail "حفظ MediaStore غير موجود"
grep -q "splitSourceDirs" app/src/main/java/com/bassam/qareebshare/TransferSourceResolver.java || fail "Split APKs غير مدعومة"
grep -q "foregroundServiceType=\"connectedDevice\"" app/src/main/AndroidManifest.xml || fail "خدمة النقل الأمامية غير مضبوطة"
grep -q "workflow_dispatch" .github/workflows/android.yml || fail "البناء اليدوي غير موجود"
if grep -Eq '^[[:space:]]*(push|pull_request):' .github/workflows/android.yml; then
  fail "البناء يعمل تلقائياً عند الدفع"
fi
if grep -Eq '^[[:space:]]*(implementation|api|compileOnly|runtimeOnly|debugImplementation|releaseImplementation)[[:space:]]' app/build.gradle; then
  fail "تم العثور على مكتبة خارجية داخل التطبيق"
fi

if command -v python >/dev/null 2>&1; then
python - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
root = Path('.')
ET.parse(root / 'app/src/main/AndroidManifest.xml')
for path in (root / 'app/src/main/res').rglob('*.xml'):
    ET.parse(path)
print('XML: OK')
PY
else
  echo 'XML: skipped (Python is not installed)'
fi

echo "========================================"
echo "QAREEB SHARE BATCH 3 CHECK: OK"
echo "Streaming transfer: present"
echo "SHA-256 verification: present"
echo "Base and Split APK sources: present"
echo "Downloads storage: present"
echo "Foreground transfer service: present"
echo "Build trigger: manual only"
echo "No APK was built"
echo "========================================"
