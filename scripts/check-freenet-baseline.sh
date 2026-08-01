#!/usr/bin/env bash
set -euo pipefail

core_dir="/workspace/freenet-core"
export GIT_CONFIG_COUNT=1
export GIT_CONFIG_KEY_0=safe.directory
export GIT_CONFIG_VALUE_0="${core_dir}"

if [[ ! -f "${core_dir}/Cargo.toml" ]]; then
  echo "Expected the sibling freenet-core checkout at ${core_dir}" >&2
  exit 1
fi

git -C "${core_dir}" diff --exit-code
git -C "${core_dir}" diff --cached --exit-code
# Freenet's integration tests execute workspace binaries such as fdev. Its CI
# builds the workspace before starting the test suite, so mirror that prerequisite.
cargo build --manifest-path "${core_dir}/Cargo.toml" --locked
cargo build --manifest-path "${core_dir}/Cargo.toml" --locked -p freenet
cargo test --manifest-path "${core_dir}/Cargo.toml" --locked -p freenet
cargo fmt --manifest-path "${core_dir}/Cargo.toml" --all -- --check
cargo clippy \
  --manifest-path "${core_dir}/Cargo.toml" \
  --locked \
  -p freenet \
  --all-targets \
  -- -D warnings
git -C "${core_dir}" diff --exit-code
