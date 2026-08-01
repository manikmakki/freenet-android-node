# Phase 2 Report

Phase: Cross-compile and link `freenet-core`
Status: PASS

Completed:
- Added the sibling Freenet core crate as a local Rust path dependency.
- Tested the requested A-E Freenet feature matrix for ARM64 Android API 28.
- Identified that the A-E failures are unsupported upstream feature
  compositions rather than Android platform failures.
- Explicitly selected `redb`, `trace`, `wasmtime-backend`, and `websocket`,
  preserving the complete current upstream default feature set.
- Cross-compiled and linked Freenet, redb, Wasmtime/Cranelift, WebSockets, and
  tracing into `libfreenet_android.so` without modifying Freenet core.
- Added `nativeFreenetBuildInfo()`. Its core version is extracted by the Rust
  build from the linked dependency's manifest; its gateway-port value and
  retained transport-generation function call come directly from Freenet core.
- Displayed the Freenet build information in the Compose UI with JNI errors
  surfaced as text.

Files added:
- `native/build.rs`
- `docs/PHASE_2_REPORT.md`

Files modified:
- `.gitignore`
- `compose.yaml`
- `native/Cargo.toml`
- `native/Cargo.lock`
- `native/src/lib.rs`
- `android/app/src/main/java/org/freenet/androidnode/NativeBridge.kt`
- `android/app/src/main/java/org/freenet/androidnode/MainActivity.kt`
- `docs/PORTING_NOTES.md`
- `README.md`

Commands run:
- Dockerized `cargo ndk` ARM64 release builds for feature cases A-E
- Dockerized `cargo ndk` ARM64 release build with E plus `trace`
- `docker compose run --rm dev scripts/check.sh`
- `(cd artifacts && sha256sum -c SHA256SUMS.txt)`
- `readelf`, `nm`, APK archive, and APK signature inspections
- Host ADB install, cold launch, UI inspection, JNI exercise, and logcat review
- Two fresh-container signing passes and an ADB replacement-install check

Tests run:
- Rust formatting, Clippy with warnings denied, and adapter unit tests
- ARM64 and x86-64 native release builds
- Android unit-test task, lint, and debug APK assembly
- Artifact checksums, ABI packaging, ELF/JNI-symbol, and APK-signature checks
- Physical ARM64 device load and UI/JNI smoke test
- Stable debug signer digest and replacement-install behavior across one-shot
  Docker containers

Observed warnings:
- Cases A-E do not form valid independent Freenet 0.2.116 configurations; the
  exact failures and adapter-level feature selections are in `PORTING_NOTES.md`.
- Runtime behavior of core subsystems is intentionally untested because Phase 2
  does not construct or start a Freenet node.
- One-shot containers initially generated different debug certificates, so ADB
  replacement was rejected. `ANDROID_USER_HOME` now resides in the persistent
  Gradle cache volume; two clean signing passes retained the same certificate.
  The state-free prototype package and its disposable data were removed during
  the smoke-test certificate migrations; they are not recoverable, but no node
  identity or node state exists before Phase 3.
- A stale generated `android/.kotlin` cache owned by UID 65534 blocked one
  compiler session. It was copied to `/tmp`, removed from the bind mount, and
  added to `.gitignore`; the clean Docker rerun passed.
- OnePlus/Oplus framework code emits device-specific runtime-flag, graphics,
  property-access, and profile warnings. No fatal exception, JNI/linker error,
  ANR, or native crash was observed.

Blockers:
- None. Gate 2 is satisfied for ARM64 Android with the complete required
  feature set.

Changes made to freenet-core:
- None.

Recommended next action:
- Begin Phase 3 in a separate run by designing the embedded runtime adapter.
  Do not infer that compile/load coverage proves node lifecycle behavior.
