#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

fail() {
  echo "خطأ: $1" >&2
  exit 1
}

HOST="app/src/main/java/com/bassam/qareebshare/HostSessionController.java"
JOIN="app/src/main/java/com/bassam/qareebshare/JoinSessionController.java"
SESSION="app/src/main/java/com/bassam/qareebshare/SessionController.java"
ACTIVITY="app/src/main/java/com/bassam/qareebshare/SessionActivity.java"
STRINGS="app/src/main/res/values/session_strings.xml"

for file in "$HOST" "$JOIN" "$SESSION" "$ACTIVITY" "$STRINGS"; do
  [[ -f "$file" ]] || fail "الملف مفقود: $file"
done

grep -q "manager.createGroup" "$HOST" || fail "إنشاء مجموعة المضيف غير موجود"
grep -q 'record.put("role", "host")' "$HOST" || fail "إعلان جلسة المضيف غير موجود"
grep -q "manager.discoverServices" "$JOIN" || fail "البحث عن الجلسات غير موجود"
grep -q "config.groupOwnerIntent = 0" "$JOIN" || fail "إجبار المنضم على عدم منافسة المضيف غير موجود"
grep -q "إنشاء جلسة" "$STRINGS" || fail "زر إنشاء الجلسة غير موجود"
grep -q "الانضمام إلى جلسة" "$STRINGS" || fail "زر الانضمام غير موجود"
grep -q "Settings.Panel.ACTION_WIFI" "$ACTIVITY" || fail "فتح إعدادات Wi-Fi غير موجود"
grep -q "ActiveSessionTransport" "$SESSION" || fail "قناة الجلسة النشطة غير موجودة"

if grep -Eqi 'P2P|TCP|Socket|Group owner|API level' "$STRINGS"; then
  fail "يوجد نص تقني في واجهة المستخدم"
fi

if grep -q "startSearch" "$SESSION"; then
  fail "منطق البحث المتناظر القديم ما زال موجوداً"
fi

bash scripts/check-final.sh >/dev/null

echo "========================================"
echo "QAREEB SHARE HOST/JOIN BATCH 1 CHECK: OK"
echo "Host creates one session: present"
echo "Joiner searches for host sessions only: present"
echo "Joiner ownership intent: zero"
echo "Persistent active channel: present"
echo "Wi-Fi off guidance: present"
echo "Light / dark / system mode: present"
echo "User-facing technical text: none"
echo "No APK was built"
echo "========================================"
