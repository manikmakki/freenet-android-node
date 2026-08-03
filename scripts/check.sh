#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Fix permissions for mounted volumes (container developer user can't write to host root dirs)
chmod -R u+rwX "$repo_root" 2>/dev/null || true

shellcheck "${repo_root}"/scripts/*.sh
cargo fmt --manifest-path "${repo_root}/native/Cargo.toml" --check
cargo clippy \
  --manifest-path "${repo_root}/native/Cargo.toml" \
  --all-targets \
  --locked \
  -- -D warnings
cargo test --manifest-path "${repo_root}/native/Cargo.toml" --locked

"${repo_root}/scripts/build-native.sh"
"${repo_root}/android/gradlew" \
  -p "${repo_root}/android" \
  --no-daemon \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleDebug

"${repo_root}/scripts/build-debug.sh"
