#!/usr/bin/env bash
set -euo pipefail

java -version
rustc --version --verbose
cargo --version
cargo ndk --version
gradle --version
sdkmanager --version
echo "Android NDK: ${ANDROID_NDK_HOME}"
