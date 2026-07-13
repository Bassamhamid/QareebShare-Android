#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

fail() {
  echo "خطأ: $1" >&2
  exit 1
}

bash scripts/check-project.sh >/dev/null
bash scripts/check-rebuild-batch1.sh >/dev/null

python - <<'PY'
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path('.')

# Every text file in the repository must be clean; do not rely only on git diff.
extensions = {'.java', '.xml', '.gradle', '.properties', '.yml', '.yaml', '.sh', '.md', '.txt'}
for path in root.rglob('*'):
    if not path.is_file() or '.git' in path.parts:
        continue
    if path.suffix not in extensions and path.name not in {'gradlew'}:
        continue
    text = path.read_text(encoding='utf-8')
    for number, line in enumerate(text.splitlines(), 1):
        if line.endswith((' ', '\t')):
            raise SystemExit(f'Trailing whitespace: {path}:{number}')

build = (root / 'app/build.gradle').read_text(encoding='utf-8')
version_code = re.search(r"versionCode\s*=\s*(\d+)", build)
version_name = re.search(r"versionName\s*=\s*'([^']+)'", build)
min_sdk = re.search(r"minSdk\s*=\s*(\d+)", build)
target_sdk = re.search(r"targetSdk\s*=\s*(\d+)", build)
if not version_code or int(version_code.group(1)) < 1:
    raise SystemExit('versionCode is invalid')
if not version_name or not re.fullmatch(r'\d+\.\d+\.\d+', version_name.group(1)):
    raise SystemExit('versionName is invalid')
if not min_sdk or min_sdk.group(1) != '23':
    raise SystemExit('minSdk must remain 23')
if not target_sdk or target_sdk.group(1) != '37':
    raise SystemExit('targetSdk must remain 37')

manifest = root / 'app/src/main/AndroidManifest.xml'
ET.parse(manifest)
for path in (root / 'app/src/main/res').rglob('*.xml'):
    ET.parse(path)

# Collect resources across every values and values-night file.
strings = set()
styles = set()
colors = set()
for path in (root / 'app/src/main/res').glob('values*/*.xml'):
    tree = ET.parse(path).getroot()
    for node in tree:
        name = node.attrib.get('name')
        if not name:
            continue
        if node.tag in {'string', 'string-array'}:
            strings.add(name)
        elif node.tag == 'style':
            styles.add(name)
        elif node.tag == 'color':
            colors.add(name)

string_refs = set()
style_refs = set()
color_refs = set()
for path in list((root / 'app/src/main/java').rglob('*.java')) + list((root / 'app/src/main/res').rglob('*.xml')):
    text = path.read_text(encoding='utf-8')
    string_refs.update(re.findall(r'R\.string\.([A-Za-z0-9_]+)', text))
    string_refs.update(re.findall(r'@string/([A-Za-z0-9_]+)', text))
    style_refs.update(re.findall(r'@style/([A-Za-z0-9_.]+)', text))
    color_refs.update(re.findall(r'@color/([A-Za-z0-9_]+)', text))

missing_strings = sorted(string_refs - strings)
missing_styles = sorted(style_refs - styles)
missing_colors = sorted(color_refs - colors)
if missing_strings:
    raise SystemExit('Missing strings: ' + ', '.join(missing_strings))
if missing_styles:
    raise SystemExit('Missing styles: ' + ', '.join(missing_styles))
if missing_colors:
    raise SystemExit('Missing colors: ' + ', '.join(missing_colors))

workflow = (root / '.github/workflows/android.yml').read_text(encoding='utf-8')
required_actions = {
    'actions/checkout@v6',
    'actions/setup-java@v5',
    'android-actions/setup-android@v4',
    'gradle/actions/setup-gradle@v6',
    'actions/upload-artifact@v6',
}
missing_actions = sorted(action for action in required_actions if action not in workflow)
if missing_actions:
    raise SystemExit('Workflow action is stale or missing: ' + ', '.join(missing_actions))
if re.search(r'^\s*(push|pull_request):', workflow, re.MULTILINE):
    raise SystemExit('Workflow must remain manual-only')
if 'if: always()\n        uses: actions/upload-artifact@v6\n        with:\n          name: qareeb-share-apks' in workflow:
    raise SystemExit('APK upload must not run after a failed build')

print('Repository structure and resources: OK')
print(f"Version: {version_name.group(1)} ({version_code.group(1)})")
PY

if grep -R --line-number --include='build.gradle' -E \
  '^[[:space:]]*(implementation|api|compileOnly|runtimeOnly|debugImplementation|releaseImplementation)[[:space:]]' .; then
  fail "تم العثور على مكتبة خارجية داخل التطبيق"
fi

echo "========================================"
echo "QAREEB SHARE REVIEWED CHECK: OK"
echo "Rebuild batch 1 checks: OK"
echo "Version checks: dynamic"
echo "Repository whitespace: clean"
echo "Workflow: Node 24 actions"
echo "APK upload: only after successful build and lint"
echo "Runtime libraries: none"
echo "No APK was built locally"
echo "========================================"
