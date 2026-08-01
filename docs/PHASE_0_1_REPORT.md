# Phase 0 and Phase 1 Report

## Phase 0

Phase: Establish a reproducible baseline
Status: PARTIAL

Completed:
- Captured the sibling Freenet checkout at commit
  `7f1f83875fd76a8ceb9d12270975e8735f4659cd` (`freenet` 0.2.116).
- Confirmed that the checkout has no registered submodules and no tracked
  modifications before or after the baseline.
- Recorded the host, Rust, Cargo, Java, Android, Gradle, and NDK versions.
- Built the Freenet workspace and `freenet` package in Docker.
- Ran the full `freenet` test and formatting suites successfully.
- Created the companion repository structure, porting notes, and JNI ADR.
- Built the Android project from the command line in the pinned container.

Files added:
- `.dockerignore`, `.gitignore`, `Dockerfile`, and `compose.yaml`
- `docs/BASELINE.md`, `docs/PORTING_NOTES.md`, and
  `docs/adr/0001-embed-freenet-with-jni.md`
- `scripts/check-freenet-baseline.sh` and `scripts/print-versions.sh`
- Android, native adapter, and build files listed in the Phase 1 report

Files modified:
- `README.md`

Commands run:
- `docker compose build dev`
- `docker compose run --rm dev scripts/print-versions.sh`
- `docker compose run --rm dev scripts/check-freenet-baseline.sh`
- `cargo build --locked` (Freenet workspace prerequisite used by upstream CI)
- `cargo build --locked -p freenet`
- `cargo test --locked -p freenet`
- `cargo fmt --all -- --check`
- `cargo clippy --locked -p freenet --all-targets -- -D warnings`

Tests run:
- Full `freenet` unit, binary, integration, and doc-test suite: passed.
- Formatting check: passed.
- Clippy with warnings denied: failed on multiple pre-existing warnings in the
  pinned, tracked-clean Freenet commit (33 errors in the library test target
  alone, plus binary targets).

Observed warnings:
- The Android SDK reports that `sdkmanager` is deprecated in favor of the new
  Android CLI.
- Freenet Clippy reports existing lints including `int_plus_one`,
  `unusual_byte_groupings`, `assertions_on_constants`, `redundant_locals`,
  `let_underscore_must_use`, and `wildcard_enum_match_arm`.

Blockers:
- Phase 0 cannot be marked PASS while the required upstream Clippy command
  fails at the recorded clean commit. Fixing those warnings is outside this
  phase's no-core-changes constraint.

Changes made to freenet-core:
- None.

Recommended next action:
- Track the upstream Clippy baseline separately before requiring a green
  `-D warnings` result from this commit.

## Phase 1

Phase: Build a JNI hello-world application
Status: PASS

Completed:
- Created a single-module Kotlin/Jetpack Compose application with application
  ID `org.freenet.androidnode`, min SDK 28, and compile/target SDK 36.
- Created an independent Rust `cdylib`; it does not depend on `freenet-core`.
- Implemented `nativePing()` and `nativeBuildInfo()` with panic containment.
- Surfaced native load and call failures in the UI.
- Cross-compiled and packaged ARM64 and x86-64 shared libraries.
- Built and v2-signed a debug APK entirely in Docker.
- Exported host-visible APK/native artifacts and a portable checksum manifest.
- Verified APK ABI entries, ELF architectures, JNI exports, checksums, and APK
  signature statically.
- Installed and cold-launched the APK on a OnePlus IN2017 running Android 13
  (API 33).
- Verified on-device that Android selected `arm64-v8a`, Kotlin loaded the native
  library, build information reported `aarch64-linux-android`, and repeated JNI
  ping calls returned `pong` while the process remained alive.

Files added:
- `android/` Gradle project, wrapper, manifest, Compose activity, bridge, and
  application resources
- `native/Cargo.toml`, `native/Cargo.lock`, `native/rust-toolchain.toml`, and
  `native/src/lib.rs`
- `scripts/build-native.sh`, `scripts/build-debug.sh`, and `scripts/check.sh`
- `artifacts/apk/freenet-android-node-debug.apk`
- `artifacts/native/arm64-v8a/libfreenet_android.so`
- `artifacts/native/x86_64/libfreenet_android.so`
- `artifacts/SHA256SUMS.txt`

Files modified:
- `README.md`

Commands run:
- `docker compose run --rm dev scripts/check.sh`
- `(cd artifacts && sha256sum -c SHA256SUMS.txt)`
- `unzip -l artifacts/apk/freenet-android-node-debug.apk`
- `readelf -h` and `nm -D` for both exported native libraries
- Android build-tools 36.0.0 `apksigner verify --verbose --print-certs`
- `adb devices -l`
- `adb -s <device-serial> install -r artifacts/apk/freenet-android-node-debug.apk`
- `adb -s <device-serial> shell am start -W -n org.freenet.androidnode/.MainActivity`
- `adb -s <device-serial> shell input tap 287 839`
- `adb -s <device-serial> exec-out uiautomator dump /dev/tty`
- `adb -s <device-serial> logcat -d --pid=<app-pid> -v brief`

Tests run:
- Shellcheck for project scripts: passed.
- Rust formatting: passed.
- Rust Clippy for all adapter targets with warnings denied: passed.
- Rust unit test: 1 passed.
- Android lint: passed.
- Android debug assembly: passed.
- APK checksums, ABI contents, ELF machine types, JNI symbols, and v2 debug
  signature: passed.
- On-device installation and JNI/UI runtime test on OnePlus IN2017: passed.
- Post-test process health and logcat crash/linker/JNI inspection: passed.

Observed warnings:
- Gradle reports `testDebugUnitTest NO-SOURCE`; Phase 1 behavior is covered by
  the Rust unit test, static APK/JNI inspection, and physical-device smoke test,
  but no Android unit test is present yet.
- The SDK manager deprecation warning is emitted during image builds.
- OnePlus/Oplus framework code emits device-specific graphics, property-access,
  and runtime-flag warnings. No fatal exception, JNI/linker failure, ANR, or
  native crash was observed.

Blockers:
- None. Gate 1 is satisfied on ARM64 hardware.

Changes made to freenet-core:
- None.

Recommended next action:
- Begin Phase 2 in a separate implementation run by adding the local
  `freenet-core` dependency and testing Android feature combinations
  incrementally. Do not attempt to start a node yet.
