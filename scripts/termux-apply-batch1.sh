#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ZIP_PATH="${1:-$HOME/storage/downloads/QareebShare-Android-batch1.zip}"
PROJECT_DIR="${2:-$HOME/QareebShare-Android}"

if [[ ! -f "$ZIP_PATH" ]]; then
  echo "لم يتم العثور على الملف: $ZIP_PATH" >&2
  exit 1
fi

mkdir -p "$PROJECT_DIR"
unzip -oq "$ZIP_PATH" -d "$PROJECT_DIR"
chmod +x "$PROJECT_DIR"/scripts/*.sh

cd "$PROJECT_DIR"
./scripts/check-project.sh

echo
printf 'تم تطبيق الدفعة الأولى في:\n%s\n' "$PROJECT_DIR"
