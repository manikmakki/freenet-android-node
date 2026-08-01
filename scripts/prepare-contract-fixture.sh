#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
core_root="$(cd "${repo_root}/../freenet-core" && pwd)"
fixture_output="${repo_root}/native/assets/test_contract_mock_aligned.wasm"
expected_sha256="fed7e208fb711822f060f030f856e97580cd9b1fc02f79e7a6dcac690bbc982c"

: "${CARGO_TARGET_DIR:?CARGO_TARGET_DIR must point to the shared Docker build target}"

CARGO_PROFILE_RELEASE_DEBUG=false \
CARGO_PROFILE_RELEASE_STRIP=debuginfo \
cargo build \
  --manifest-path "${core_root}/Cargo.toml" \
  --package test-contract-mock-aligned \
  --target wasm32-unknown-unknown \
  --release

compiled_fixture="${CARGO_TARGET_DIR}/wasm32-unknown-unknown/release/test_contract_mock_aligned.wasm"
actual_sha256="$(sha256sum "${compiled_fixture}" | cut -d ' ' -f 1)"
if [[ "${actual_sha256}" != "${expected_sha256}" ]]; then
  echo "Unexpected test-contract-mock-aligned digest: ${actual_sha256}" >&2
  echo "Expected digest for the recorded Freenet baseline: ${expected_sha256}" >&2
  exit 1
fi

mkdir -p "$(dirname "${fixture_output}")"
install -m 0644 "${compiled_fixture}" "${fixture_output}"
echo "Prepared ${fixture_output} (${actual_sha256})"
