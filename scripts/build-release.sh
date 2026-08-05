#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
artifact_root="${repo_root}/artifacts"

gradle_version_args=()
if [[ -n "${APP_VERSION_CODE:-}" ]]; then
  gradle_version_args+=("-PappVersionCode=${APP_VERSION_CODE}")
fi
if [[ -n "${APP_VERSION_NAME:-}" ]]; then
  gradle_version_args+=("-PappVersionName=${APP_VERSION_NAME}")
fi

"${repo_root}/scripts/build-native.sh"
"${repo_root}/android/gradlew" \
  -p "${repo_root}/android" \
  --no-daemon \
  "${gradle_version_args[@]}" \
  :app:assembleRelease

mkdir -p \
  "${artifact_root}/apk" \
  "${artifact_root}/native/arm64-v8a" \
  "${artifact_root}/native/x86_64"

install -m 0644 \
  "${repo_root}/android/app/build/outputs/apk/release/app-release.apk" \
  "${artifact_root}/apk/freenet-android-node-release.apk"
install -m 0644 \
  "${repo_root}/android/app/src/main/jniLibs/arm64-v8a/libfreenet_android.so" \
  "${artifact_root}/native/arm64-v8a/libfreenet_android.so"
install -m 0644 \
  "${repo_root}/android/app/src/main/jniLibs/x86_64/libfreenet_android.so" \
  "${artifact_root}/native/x86_64/libfreenet_android.so"

(
  cd "${artifact_root}"
  sha256sum \
    apk/freenet-android-node-release.apk \
    native/arm64-v8a/libfreenet_android.so \
    native/x86_64/libfreenet_android.so \
    > SHA256SUMS.txt
)

echo "Exported build artifacts to ${artifact_root}"
