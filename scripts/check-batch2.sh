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
  "app/src/main/java/com/bassam/qareebshare/NearbyPermissionManager.java"
  "app/src/main/java/com/bassam/qareebshare/PeerDevice.java"
  "app/src/main/java/com/bassam/qareebshare/PeersAdapter.java"
  "app/src/main/res/layout/row_peer.xml"
  "BATCH_02.md"
)

for file in "${required[@]}"; do
  [[ -f "$file" ]] || fail "الملف مفقود: $file"
done

grep -q "minSdk = 23" app/build.gradle || fail "minSdk تغير"
grep -q "targetSdk = 37" app/build.gradle || fail "targetSdk تغير"
grep -q "discoverPeers" app/src/main/java/com/bassam/qareebshare/WifiDirectController.java || fail "اكتشاف الأجهزة غير موجود"
grep -q "createGroup" app/src/main/java/com/bassam/qareebshare/WifiDirectController.java || fail "وضع الاستقبال غير موجود"
if [[ -f app/src/main/java/com/bassam/qareebshare/TransferEngine.java ]]; then
  grep -q "ServerSocket" app/src/main/java/com/bassam/qareebshare/TransferEngine.java || fail "خادم TCP غير موجود"
  grep -q "new Socket" app/src/main/java/com/bassam/qareebshare/TransferEngine.java || fail "عميل TCP غير موجود"
elif [[ -f app/src/main/java/com/bassam/qareebshare/LocalHandshake.java ]]; then
  grep -q "ServerSocket" app/src/main/java/com/bassam/qareebshare/LocalHandshake.java || fail "خادم TCP غير موجود"
  grep -q "new Socket" app/src/main/java/com/bassam/qareebshare/LocalHandshake.java || fail "عميل TCP غير موجود"
else
  fail "طبقة TCP غير موجودة"
fi
grep -q "workflow_dispatch" .github/workflows/android.yml || fail "التشغيل اليدوي للبناء غير موجود"
if grep -Eq '^[[:space:]]*(push|pull_request):' .github/workflows/android.yml; then
  fail "البناء ما زال يعمل تلقائياً عند الدفع"
fi

echo "QAREEB SHARE BATCH 2 BASE: OK"
