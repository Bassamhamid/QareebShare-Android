#!/usr/bin/env sh
set -eu

GRADLE_VERSION="9.4.1"
GRADLE_SHA256="2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb"
BASE_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}/qareeb-bootstrap"
ZIP_FILE="$BASE_DIR/gradle-$GRADLE_VERSION-bin.zip"
DIST_DIR="$BASE_DIR/gradle-$GRADLE_VERSION"
GRADLE_BIN="$DIST_DIR/bin/gradle"

if [ ! -x "$GRADLE_BIN" ]; then
    mkdir -p "$BASE_DIR"
    if [ ! -f "$ZIP_FILE" ]; then
        if command -v curl >/dev/null 2>&1; then
            curl -L --fail --retry 3 -o "$ZIP_FILE" \
                "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
        elif command -v wget >/dev/null 2>&1; then
            wget -O "$ZIP_FILE" \
                "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
        else
            echo "curl or wget is required to download Gradle." >&2
            exit 1
        fi
    fi

    if command -v sha256sum >/dev/null 2>&1; then
        ACTUAL_SHA="$(sha256sum "$ZIP_FILE" | awk '{print $1}')"
        if [ "$ACTUAL_SHA" != "$GRADLE_SHA256" ]; then
            echo "Gradle archive checksum mismatch." >&2
            rm -f "$ZIP_FILE"
            exit 1
        fi
    fi

    command -v unzip >/dev/null 2>&1 || {
        echo "unzip is required." >&2
        exit 1
    }
    rm -rf "$DIST_DIR"
    unzip -q "$ZIP_FILE" -d "$BASE_DIR"
fi

exec "$GRADLE_BIN" "$@"
