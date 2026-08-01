#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
artifact_root="${repo_root}/artifacts"

"${repo_root}/scripts/build-native.sh"
"${repo_root}/android/gradlew" \
  -p "${repo_root}/android" \
  --no-daemon \
  :app:assembleDebug \
  :app:assembleDebugAndroidTest

mkdir -p \
  "${artifact_root}/apk" \
  "${artifact_root}/native/arm64-v8a" \
  "${artifact_root}/native/x86_64"

install -m 0644 \
  "${repo_root}/android/app/build/outputs/apk/debug/app-debug.apk" \
  "${artifact_root}/apk/freenet-android-node-debug.apk"
install -m 0644 \
  "${repo_root}/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" \
  "${artifact_root}/apk/freenet-android-node-debug-androidTest.apk"
install -m 0644 \
  "${repo_root}/android/app/src/main/jniLibs/arm64-v8a/libfreenet_android.so" \
  "${artifact_root}/native/arm64-v8a/libfreenet_android.so"
install -m 0644 \
  "${repo_root}/android/app/src/main/jniLibs/x86_64/libfreenet_android.so" \
  "${artifact_root}/native/x86_64/libfreenet_android.so"

(
  cd "${artifact_root}"
  sha256sum \
    apk/freenet-android-node-debug.apk \
    apk/freenet-android-node-debug-androidTest.apk \
    native/arm64-v8a/libfreenet_android.so \
    native/x86_64/libfreenet_android.so \
    > SHA256SUMS.txt
)

echo "Exported build artifacts to ${artifact_root}"
