# Phase 3 Report

Phase: Create the embedded node runtime adapter
Status: PASS

Completed:
- Added `AndroidNodeConfig`, `NodeState`, `NodeStatus`, `NodeRuntime`,
  `NodeCommand`, and `NodeError` in the Rust adapter.
- Implemented controlled lifecycle transitions for `Stopped`, `Starting`,
  `RunningLocal`, `RunningNetwork`, `Stopping`, and `Failed`.
- Added structured JNI start, stop, status, and bounded recent-log endpoints;
  panic containment remains at every JNI entry point.
- Started Freenet on a named native thread with its own multi-thread Tokio
  runtime. JNI calls only validate/submit work or read mutex-protected state.
- Mirrored Freenet's local CLI initialization with `ConfigArgs`, the local
  redb executor, and `run_local_node`.
- Passed Android's `filesDir`, `cacheDir`, `noBackupFilesDir`, database,
  contract, configuration, and log locations explicitly and validated the
  derived Freenet layout before opening storage.
- Added 250 ms status polling and start/stop/log controls to the Compose UI.
- Added a host-side ADB soak script that tolerates a device keyguard without
  changing the device's display-timeout settings.

Files added:
- `native/src/runtime.rs`
- `scripts/smoke-phase3-adb.sh`
- `docs/PHASE_3_REPORT.md`

Files modified:
- `android/app/src/main/AndroidManifest.xml`
- `android/app/src/main/java/org/freenet/androidnode/MainActivity.kt`
- `android/app/src/main/java/org/freenet/androidnode/NativeBridge.kt`
- `native/Cargo.toml`
- `native/Cargo.lock`
- `native/src/lib.rs`
- `docs/PORTING_NOTES.md`
- `README.md`

Commands run:
- `docker compose run --rm dev scripts/check.sh`
- `(cd artifacts && sha256sum -c SHA256SUMS.txt)`
- `adb -s 196ffc6e install -r artifacts/apk/freenet-android-node-debug.apk`
- `ADB_SERIAL=196ffc6e PHASE3_CYCLES=20 scripts/smoke-phase3-adb.sh`
- Host ADB UI, app-private storage, and per-process logcat inspections

Tests run:
- Shellcheck for all repository scripts
- Rust formatting and Clippy with warnings denied
- Seven Rust unit tests, including path validation, response envelopes,
  duplicate Stop, invalid overlapping starts, and a 20-cycle state model
- ARM64 and x86-64 Android release native builds
- Android unit-test task, lint, debug APK assembly, and artifact checksums
- Physical ARM64 API 33 lifecycle smoke and 20-cycle soak
- Controlled duplicate Start (`NODE_ALREADY_RUNNING`) and duplicate Stop
- Repeated reopening of the same 1,056,768-byte app-private redb file
- Final app-process logcat review: no fatal exception, native crash, or ANR

Shutdown finding:
- Freenet's network entry point exposes `ShutdownHandle`; its local entry point
  does not. The adapter selects `run_local_node` against a cooperative stop
  command, drops the local future so its WebSocket task guards tear down, and
  shuts down the owned Tokio runtime with a five-second grace period. It never
  calls `abort()`, `exit()`, or terminates the Android process.

Observed warnings:
- The device's Oplus framework logs routine graphics/frame-timing messages.
  No lifecycle request blocked long enough to cause an ANR, and the UI remained
  responsive throughout the automated button-driven soak.
- The x86-64 library is compiled, linked, linted, and packaged but still lacks
  emulator runtime coverage; that remains optional in the plan.

Blockers:
- None. Gate 3 is satisfied while the Activity remains open.

Changes made to freenet-core:
- None.

Recommended next action:
- Begin Phase 4 in a separate run and prove WASM contract execution on Android.
  Do not begin foreground-service work yet; that remains a later phase.
