#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

fail() {
  echo "خطأ: $1" >&2
  exit 1
}

required=(
  app/build.gradle
  app/src/main/AndroidManifest.xml
  app/src/main/java/com/bassam/qareebshare/SessionActivity.java
  app/src/main/java/com/bassam/qareebshare/SessionController.java
  app/src/main/java/com/bassam/qareebshare/HostSessionController.java
  app/src/main/java/com/bassam/qareebshare/JoinSessionController.java
  app/src/main/java/com/bassam/qareebshare/ActiveSessionTransport.java
  app/src/main/java/com/bassam/qareebshare/P2pSessionCleaner.java
  app/src/main/res/layout/activity_session.xml
  app/src/main/res/values/session_strings.xml
  .github/workflows/android.yml
)

for file in "${required[@]}"; do
  [[ -f "$file" ]] || fail "الملف مفقود: $file"
done

VERSION_NAME="$(sed -n "s/.*versionName = '\([^']*\)'.*/\1/p" app/build.gradle | head -1)"
VERSION_CODE="$(sed -n 's/.*versionCode = \([0-9][0-9]*\).*/\1/p' app/build.gradle | head -1)"
[[ "$VERSION_NAME" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "رقم الإصدار غير صالح"
[[ "$VERSION_CODE" =~ ^[1-9][0-9]*$ ]] || fail "versionCode غير صالح"

grep -q "minSdk = 23" app/build.gradle || fail "Android 6 غير مضبوط كحد أدنى"
grep -q "targetSdk = 37" app/build.gradle || fail "targetSdk غير مضبوط"
grep -q "workflow_dispatch" .github/workflows/android.yml || fail "البناء اليدوي غير موجود"
grep -q "app/build/outputs/apk/debug/app-debug.apk" .github/workflows/android.yml || fail "مسار APK التجريبي غير مضبوط"
if grep -q "app-release-unsigned.apk" .github/workflows/android.yml; then
  fail "ملف Release غير الموقع ما زال مرفوعاً للمستخدم"
fi

if [[ -f app/src/main/java/com/bassam/qareebshare/SessionTransport.java ]]; then
  fail "محرك الجلسة القديم ما زال موجوداً"
fi

if grep -R --line-number --include='build.gradle' -E '^[[:space:]]*(implementation|api|runtimeOnly|debugImplementation|releaseImplementation)[[:space:]]' .; then
  fail "تم العثور على مكتبة تشغيل خارجية"
fi

python - <<'PY'
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path('.')
manifest = root / 'app/src/main/AndroidManifest.xml'
ET.parse(manifest)
for path in (root / 'app/src/main/res').rglob('*.xml'):
    ET.parse(path)

strings = set()
styles = set()
for path in (root / 'app/src/main/res').glob('values*/*.xml'):
    tree = ET.parse(path).getroot()
    for node in tree:
        name = node.attrib.get('name')
        if node.tag == 'string' and name:
            strings.add(name)
        if node.tag == 'style' and name:
            styles.add(name)

string_refs = set()
style_refs = set()
paths = list((root / 'app/src/main/java').rglob('*.java'))
paths += list((root / 'app/src/main/res').rglob('*.xml'))
for path in paths:
    text = path.read_text(encoding='utf-8')
    string_refs.update(re.findall(r'R\.string\.([A-Za-z0-9_]+)', text))
    string_refs.update(re.findall(r'@string/([A-Za-z0-9_]+)', text))
    style_refs.update(re.findall(r'@style/([A-Za-z0-9_.]+)', text))

missing_strings = sorted(string_refs - strings)
if missing_strings:
    raise SystemExit('Missing string resources: ' + ', '.join(missing_strings))
missing_styles = sorted(style_refs - styles)
if missing_styles:
    raise SystemExit('Missing style resources: ' + ', '.join(missing_styles))

manifest_text = manifest.read_text(encoding='utf-8')
java_root = root / 'app/src/main/java/com/bassam/qareebshare'
classes = {path.stem for path in java_root.glob('*.java')}
for class_name in re.findall(r'android:name="\.([A-Za-z0-9_]+)"', manifest_text):
    if class_name not in classes:
        raise SystemExit('Manifest class is missing: ' + class_name)

for path in root.rglob('*'):
    if not path.is_file() or '.git' in path.parts:
        continue
    if path.suffix.lower() not in {'.java', '.xml', '.gradle', '.properties', '.md', '.sh', '.yml', '.yaml'}:
        continue
    for number, line in enumerate(path.read_text(encoding='utf-8').splitlines(), 1):
        if line.rstrip() != line:
            raise SystemExit(f'Trailing whitespace: {path}:{number}')

print('Repository structure and resources: OK')
PY

./scripts/check-project.sh >/dev/null

echo "========================================"
echo "QAREEB SHARE FINAL CHECK: OK"
echo "Version: $VERSION_NAME ($VERSION_CODE)"
echo "Host / join architecture: present"
echo "Installable artifact: debug APK only"
echo "Runtime libraries: none"
echo "No APK was built locally"
echo "========================================"
