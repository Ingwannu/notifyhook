#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APK_PATH="$ROOT_DIR/app/build/outputs/apk/release/app-release.apk"
MAPPING_PATH="$ROOT_DIR/app/build/outputs/mapping/release/mapping.txt"
METADATA_PATH="$ROOT_DIR/app/build/outputs/apk/release/output-metadata.json"
VERSION_NAME="$(grep -oE 'versionName = "[^"]+"' "$ROOT_DIR/app/build.gradle.kts" | head -n 1 | cut -d'"' -f2)"
DIST_DIR="$ROOT_DIR/dist/release-$VERSION_NAME"
GRADLE_CMD=()

if [[ -z "$VERSION_NAME" ]]; then
    printf 'Could not detect versionName from app/build.gradle.kts\n' >&2
    exit 1
fi

if [[ -x "$ROOT_DIR/gradlew" && -f "$ROOT_DIR/gradle/wrapper/gradle-wrapper.jar" ]]; then
    GRADLE_CMD=("$ROOT_DIR/gradlew")
elif command -v gradle >/dev/null 2>&1; then
    GRADLE_CMD=(gradle)
else
    printf 'Could not find a usable Gradle command. Install Gradle or restore gradle/wrapper/gradle-wrapper.jar.\n' >&2
    exit 1
fi

rm -rf "$DIST_DIR"
"${GRADLE_CMD[@]}" clean :app:assembleRelease
mkdir -p "$DIST_DIR"
cp "$APK_PATH" "$DIST_DIR/notifyhook-v$VERSION_NAME-obfuscated.apk"
cp "$MAPPING_PATH" "$DIST_DIR/mapping.txt"
cp "$METADATA_PATH" "$DIST_DIR/output-metadata.json"

printf 'Obfuscated release created:\n'
printf 'APK: %s\n' "$DIST_DIR/notifyhook-v$VERSION_NAME-obfuscated.apk"
printf 'Mapping: %s\n' "$DIST_DIR/mapping.txt"
