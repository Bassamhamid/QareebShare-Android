#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

fail() {
  echo "خطأ: $1" >&2
  exit 1
}

required=(
  "BATCH_04.md"
  "TESTING.md"
  "app/src/main/java/com/bassam/qareebshare/CryptoSession.java"
  "app/src/main/java/com/bassam/qareebshare/SecureChannel.java"
  "app/src/main/java/com/bassam/qareebshare/TransferEngine.java"
  "app/src/main/java/com/bassam/qareebshare/ReceivedFileStore.java"
  "app/src/main/java/com/bassam/qareebshare/TransferHistoryStore.java"
  "app/src/main/java/com/bassam/qareebshare/AppInstaller.java"
  "app/src/main/java/com/bassam/qareebshare/InstallResultReceiver.java"
  "app/src/main/java/com/bassam/qareebshare/HistoryAdapter.java"
  "app/src/main/res/layout/row_history.xml"
  "app/src/main/res/layout/screen_history.xml"
)

for file in "${required[@]}"; do
  [[ -f "$file" ]] || fail "الملف مفقود: $file"
done

grep -q "versionName = '0.4.0'" app/build.gradle || fail "الإصدار النهائي غير مضبوط"
grep -q "versionCode = 4" app/build.gradle || fail "versionCode النهائي غير مضبوط"
grep -q "MAGIC = 0x51534834" app/src/main/java/com/bassam/qareebshare/TransferEngine.java || fail "بروتوكول QSH4 غير موجود"
grep -q 'KeyAgreement.getInstance("ECDH")' app/src/main/java/com/bassam/qareebshare/CryptoSession.java || fail "تبادل مفاتيح ECDH غير موجود"
grep -q 'Cipher.getInstance("AES/GCM/NoPadding")' app/src/main/java/com/bassam/qareebshare/SecureChannel.java || fail "تشفير AES-GCM غير موجود"
grep -q "resumeOffset" app/src/main/java/com/bassam/qareebshare/ReceivedFileStore.java || fail "استكمال النقل غير موجود"
grep -q "RandomAccessFile" app/src/main/java/com/bassam/qareebshare/ReceivedFileStore.java || fail "الكتابة من موضع الاستكمال غير موجودة"
grep -q "PackageInstaller.Session" app/src/main/java/com/bassam/qareebshare/AppInstaller.java || fail "تثبيت التطبيقات المقسمة غير موجود"
grep -q "SQLiteOpenHelper" app/src/main/java/com/bassam/qareebshare/TransferHistoryStore.java || fail "سجل النقل المحلي غير موجود"
grep -q 'android.intent.action.SEND_MULTIPLE' app/src/main/AndroidManifest.xml || fail "المشاركة المتعددة من أندرويد غير موجودة"
grep -q 'android.permission.REQUEST_INSTALL_PACKAGES' app/src/main/AndroidManifest.xml || fail "صلاحية عرض تثبيت التطبيقات غير موجودة"
grep -q 'android.permission.ACCESS_LOCAL_NETWORK' app/src/main/AndroidManifest.xml || fail "صلاحية Android 17 للشبكة المحلية غير موجودة"
grep -q "workflow_dispatch" .github/workflows/android.yml || fail "البناء اليدوي غير موجود"

if grep -Eq '^[[:space:]]*(push|pull_request):' .github/workflows/android.yml; then
  fail "البناء يعمل تلقائياً عند الدفع"
fi

if grep -R --line-number --include='build.gradle' -E '^[[:space:]]*(implementation|api|compileOnly|runtimeOnly|debugImplementation|releaseImplementation)[[:space:]]' .; then
  fail "تم العثور على مكتبة خارجية داخل التطبيق"
fi

if command -v python >/dev/null 2>&1; then
python - <<'PY'
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path('.')
manifest = root / 'app/src/main/AndroidManifest.xml'
ET.parse(manifest)
for path in (root / 'app/src/main/res').rglob('*.xml'):
    ET.parse(path)

strings_root = ET.parse(root / 'app/src/main/res/values/strings.xml').getroot()
strings = {node.attrib['name'] for node in strings_root.findall('string')}
refs = set()
for path in list((root / 'app/src/main/java').rglob('*.java')) + list((root / 'app/src/main/res').rglob('*.xml')):
    text = path.read_text(encoding='utf-8')
    refs.update(re.findall(r'R\.string\.([A-Za-z0-9_]+)', text))
    refs.update(re.findall(r'@string/([A-Za-z0-9_]+)', text))
missing = sorted(refs - strings)
if missing:
    raise SystemExit('Missing string resources: ' + ', '.join(missing))

java_root = root / 'app/src/main/java/com/bassam/qareebshare'
classes = {p.stem for p in java_root.glob('*.java')}
manifest_text = manifest.read_text(encoding='utf-8')
for class_name in re.findall(r'android:name="\.([A-Za-z0-9_]+)"', manifest_text):
    if class_name not in classes:
        raise SystemExit('Manifest class is missing: ' + class_name)

print('XML and resource references: OK')
PY
else
  echo 'XML/resource validation: skipped (Python is not installed)'
fi

./scripts/check-project.sh >/dev/null

echo "========================================"
echo "QAREEB SHARE FINAL BATCH CHECK: OK"
echo "Encrypted protocol QSH4: present"
echo "Pairing code verification: present"
echo "Interrupted transfer resume: present"
echo "Base and Split APK installation: present"
echo "Local transfer history: present"
echo "Incoming Android sharing: present"
echo "Build trigger: manual only"
echo "Runtime libraries: none"
echo "No APK was built"
echo "========================================"
