# Freenet Android Node Prototype

## Codex Implementation Brief

### Objective

Build a prototype Android application that embeds `freenet-core` and allows an Android device to operate as a Freenet node.

The first usable version should:

* Install as a sideloaded APK on a modern ARM64 Android phone.
* Let the user explicitly start and stop the node.
* Run the node inside an Android foreground service.
* Display a persistent notification while the node is running.
* Embed the Rust Freenet core through a JNI-compatible shared library.
* Store node data in Android application-private storage.
* Successfully execute a Freenet contract in local mode.
* Eventually connect to the Freenet network and maintain peer connections.
* Shut down cleanly without corrupting node state.

This is initially an engineering prototype, not a Play Store release.

---

## 1. Project boundaries

### Included in the prototype

* Kotlin Android application.
* Jetpack Compose user interface.
* Rust native library compiled as an Android `.so`.
* JNI boundary between Kotlin and Rust.
* ARM64 physical-device support.
* x86-64 emulator support when practical.
* Local-node mode.
* Freenet WASM contract execution.
* Network-node mode.
* Foreground-service lifecycle.
* Basic node status, logs, and settings.
* Wi-Fi-only operating mode.
* Graceful shutdown and restart.

### Explicitly out of scope for the first prototype

* Google Play publication.
* Starting automatically at boot.
* Invisible or unrestricted background execution.
* Downloading or replacing native application code.
* Freenet self-update behavior.
* Embedded Freenet web applications or an Android WebView.
* Guaranteed inbound connectivity through carrier-grade NAT.
* Multi-node orchestration.
* Full production-grade Android Keystore integration.
* Supporting every Android ABI.
* Modifying Freenet routing, transport, or contract semantics.

---

## 2. Repository strategy

Do not begin by adding Android code directly to `freenet-core`.

Create a companion repository beside the Freenet repository:

```text
workspace/
├── freenet-core/
└── freenet-android-node/
```

The Android native crate should initially depend on the local Freenet checkout:

```toml
freenet = {
    path = "../../freenet-core/crates/core",
    default-features = false
}
```

Record the exact Freenet commit used by the prototype in:

```text
freenet-android-node/docs/BASELINE.md
```

This arrangement allows Android compatibility work to proceed without creating speculative changes in the upstream repository.

Only modify `freenet-core` when:

1. A specific Android compilation or runtime blocker has been reproduced.
2. The smallest reasonable fix has been identified.
3. The proposed change cannot be implemented in the Android adapter.
4. A Freenet issue has been opened and approved when the change alters behavior, adds a feature, or reshapes an API.

Freenet requires an approved issue before feature, behavior, or API work, and expects one logical change per pull request.

---

## 3. Required reading before changing code

Before implementing anything, read:

```text
freenet-core/AGENTS.md
freenet-core/CONTRIBUTING.md
freenet-core/.claude/rules/code-style.md
freenet-core/.claude/rules/testing.md
freenet-core/crates/core/src/lib.rs
freenet-core/crates/core/src/bin/freenet.rs
freenet-core/crates/core/Cargo.toml
```

When working in a specific core module, also read its corresponding rule file:

```text
transport/          → .claude/rules/transport.md
contract/ or WASM   → .claude/rules/contracts.md
operations/         → .claude/rules/operations.md
ring/ or router/    → .claude/rules/ring.md
```

The core currently exports library entry points including `run_local_node`, `run_network_node`, and `ShutdownHandle`. Use these library interfaces and the CLI startup code as implementation references. Do not launch the desktop CLI as a child process.

---

## 4. Working rules for Codex

Follow these rules throughout the project:

1. Work one phase at a time.
2. Do not attempt the entire app in a single change.
3. Make one logical commit per completed phase.
4. Keep a running `docs/PORTING_NOTES.md`.
5. Record exact build commands and complete error messages.
6. Do not hide failures by disabling tests or broadly removing functionality.
7. Do not add platform conditionals to `freenet-core` until the actual failure is understood.
8. Prefer Android-specific behavior in the adapter rather than in the core.
9. Keep JNI narrow and use simple serialized values.
10. Do not block Android’s main UI thread.
11. Do not allow Rust panics to cross the JNI boundary.
12. Do not log node secrets, tokens, private keys, or raw key material.
13. Every Rust `unsafe` block must have a meaningful `// SAFETY:` comment.
14. Run formatting, linting, and tests before declaring a phase complete.
15. Stop at each explicit gate and report the result before proceeding.

The Freenet repository currently pins Rust 1.94.0, though Codex should treat the checked-in `rust-toolchain.toml` as authoritative rather than hard-coding that version elsewhere.

---

# Phase 0: Establish a reproducible baseline

## Tasks

1. Clone or update `freenet-core`.
2. Initialize its submodules:

```bash
git submodule update --init --recursive
```

3. Record:

```text
Freenet commit
Rust version
Cargo version
Java version
Android Studio version
Gradle version
Android Gradle Plugin version
Android SDK version
Android NDK version
Host operating system
```

4. Confirm the normal host build works:

```bash
cargo build -p freenet
cargo test -p freenet
cargo fmt --check
cargo clippy -p freenet --all-targets -- -D warnings
```

5. Create the companion repository:

```text
freenet-android-node/
├── README.md
├── docs/
│   ├── BASELINE.md
│   └── PORTING_NOTES.md
├── android/
└── native/
```

6. Add a short architecture decision record:

```text
docs/adr/0001-embed-freenet-with-jni.md
```

It should explain why the app embeds the Rust library instead of running the CLI.

## Acceptance criteria

* The host Freenet build succeeds.
* The exact Freenet commit is recorded.
* The Android and Rust toolchain versions are documented.
* No `freenet-core` source files have been modified.
* The companion repository builds an empty Android project.

---

# Phase 1: Build a JNI hello-world application

This phase must not link `freenet-core` yet.

## Android application

Create an Android application using:

* Kotlin.
* Jetpack Compose.
* A single application module.
* Working application ID such as `org.freenet.androidnode`.
* `minSdk` 28 for the prototype.
* The current installed stable `compileSdk` and `targetSdk`.
* ARM64 as the primary ABI.
* x86-64 as an optional emulator ABI.

The initial screen should show:

```text
Freenet Android Node

Native bridge: Not loaded / Loaded
Native version: <value>

[Run native test]
```

## Rust native crate

Create:

```text
native/
├── Cargo.toml
└── src/
    └── lib.rs
```

Use:

```toml
[lib]
crate-type = ["cdylib"]
```

Implement only two JNI methods:

```text
nativePing() -> String
nativeBuildInfo() -> String
```

Return static diagnostic information such as:

```text
pong
Rust JNI bridge 0.1.0, aarch64-linux-android
```

Use `cargo-ndk` to produce the normal Android `jniLibs` layout. `cargo-ndk` supports Android Rust targets and can write `.so` files directly into the ABI-specific directory structure expected by Android.

Provide a reproducible script:

```text
scripts/build-native.sh
```

Conceptual command:

```bash
cargo ndk \
  --target arm64-v8a \
  --target x86_64 \
  --output-dir ../android/app/src/main/jniLibs \
  build --release
```

Do not make the Gradle build depend on a developer-specific absolute NDK path.

## Acceptance criteria

* The APK builds from the command line.
* The APK installs on an emulator or physical device.
* Kotlin successfully loads the native library.
* The UI displays the string returned by Rust.
* JNI errors are surfaced in the UI rather than crashing silently.
* `README.md` contains exact build and installation commands.

## Gate 1

Do not proceed until Kotlin-to-Rust JNI works independently of Freenet.

---

# Phase 2: Cross-compile and link `freenet-core`

## Goal

Determine whether the current Freenet library can compile for:

```text
aarch64-linux-android
```

Do not attempt to start a node yet.

## Tasks

Add the local Freenet path dependency to the native crate.

Test feature combinations incrementally:

```text
A. default-features = false
B. Add redb
C. Add wasmtime-backend
D. Add websocket
E. redb + wasmtime-backend + websocket
```

The current default Freenet feature set includes `redb`, tracing, WebSockets, and the Wasmtime backend, so testing these separately should make the first incompatible dependency easier to identify.

Add a native function that proves the Freenet crate is linked:

```text
nativeFreenetBuildInfo() -> String
```

Do not fake success by returning a hard-coded version from Kotlin. The value must be generated from the Rust build that links the Freenet dependency.

For every compilation blocker, add an entry to `docs/PORTING_NOTES.md`:

```text
Dependency or module:
Android target:
Feature combination:
Exact compiler or linker error:
Likely platform assumption:
Can it be solved in the adapter?
Would it require a core change?
Smallest proposed solution:
Relevant upstream issue:
```

Likely areas to inspect include:

* OS keyring integration.
* Signal handling.
* Process-spawning support.
* Hostname and directory discovery.
* File notification APIs.
* C or C++ dependencies.
* Redb filesystem behavior.
* Tokio target-specific features.
* Wasmtime Android support.
* UDP socket batching or Unix-specific socket behavior.

Do not preemptively patch all of these. Only respond to reproduced failures.

## Acceptance criteria

* `libfreenet_android.so` links against `freenet-core`.
* The APK loads the resulting library.
* The UI displays Freenet build information from Rust.
* The selected feature set is documented.
* Every workaround has a written explanation.
* No unnecessary core functionality has been disabled.

## Gate 2

Do not proceed until Freenet and the required WASM backend compile into an Android-loadable native library.

If this gate fails, stop and report:

* The smallest failing dependency.
* The exact error.
* The minimal proposed fix.
* Whether that fix requires an upstream issue.

---

# Phase 3: Create the embedded node runtime adapter

## Goal

Create a stable Rust interface between Android and the Freenet node.

## Rust architecture

Implement these conceptual types:

```rust
AndroidNodeConfig
NodeState
NodeStatus
NodeRuntime
NodeCommand
NodeError
```

Suggested node states:

```text
Stopped
Starting
RunningLocal
RunningNetwork
Stopping
Failed
```

The state machine must reject invalid transitions such as:

```text
Starting while already running
Starting while stopping
Stopping while already stopped
Starting local and network nodes simultaneously
```

## JNI interface

Expose a deliberately small interface:

```text
nativeStartLocalNode(configJson: String) -> String
nativeStopNode() -> String
nativeGetNodeStatus() -> String
nativeGetRecentLogs(maxEntries: Int) -> String
```

Return structured JSON envelopes:

```json
{
  "ok": true,
  "data": {},
  "error": null
}
```

or:

```json
{
  "ok": false,
  "data": null,
  "error": {
    "code": "NODE_ALREADY_RUNNING",
    "message": "The node is already running"
  }
}
```

Do not expose internal Rust pointers, Freenet structures, or Tokio handles to Kotlin.

## Runtime model

* Start a dedicated native thread for the node.
* Construct the Tokio runtime on that thread.
* Keep JNI calls short.
* Have JNI submit commands to the node thread.
* Poll status from Kotlin initially instead of implementing complex callbacks.
* Catch Rust panics at the JNI boundary.
* Convert errors to structured responses.
* Use the Freenet library entry points and mirror initialization patterns from the CLI.
* Use `ShutdownHandle` where the current API provides it.
* Do not emulate shutdown with `abort()`, `exit()`, or process termination.

Android must pass all filesystem locations explicitly:

```text
filesDir
cacheDir
noBackupFilesDir
database directory
contract directory
configuration directory
log directory
```

Rust must not infer a desktop home directory.

## Acceptance criteria

* Local mode starts from a button in the Android app.
* Status changes from `Stopped` to `Starting` to `RunningLocal`.
* The node can be stopped through JNI.
* The node completes graceful shutdown.
* Twenty start/stop cycles complete without a crash.
* The database can be reopened after restart.
* Pressing Start twice returns a controlled error.
* Pressing Stop twice returns a controlled result.
* No operation blocks the Compose UI thread.

## Gate 3

Do not begin foreground-service work until node startup and shutdown are reliable while the Activity remains open.

---

# Phase 4: Prove WASM contract execution on Android

## Goal

Prove that Freenet’s actual contract runtime works on a physical Android device.

This is a major feasibility gate.

## Tasks

1. Locate the smallest existing Freenet contract fixture used by integration tests.
2. Reuse an existing fixture rather than inventing a new contract format.
3. Start the embedded local node.
4. Submit a simple operation through the supported client or local-node interface.
5. Store contract state.
6. Read the state back.
7. Verify the expected result.
8. Stop the node.
9. Restart it and verify persistence where applicable.

Create an Android instrumentation test when practical.

Capture:

```text
Contract load time
First execution time
Subsequent execution time
Peak memory usage
Result
Any Wasmtime warnings
Any Android linker warnings
```

## Acceptance criteria

* A real Freenet WASM contract executes on ARM64 Android.
* A contract operation produces a verifiable result.
* The process does not crash during WASM initialization.
* The node shuts down cleanly after contract execution.
* The test is reproducible from documented commands.

## Gate 4

If contract execution fails, stop further feature work.

Report whether the failure is:

```text
Compilation
Dynamic linking
Executable-memory policy
Wasmtime initialization
Contract loading
Filesystem access
Memory exhaustion
Freenet-specific behavior
```

Do not continue to network mode while contract execution is unproven.

---

# Phase 5: Move node ownership into a foreground service

## Goal

Allow the node to remain active when the Activity is backgrounded or the screen turns off.

## Android components

Create:

```text
MainActivity
NodeService
NodeRepository
NodeViewModel
NodeNotificationManager
```

`NodeService` should:

1. Be started only after explicit user action.
2. Call `startForeground()` immediately.
3. Own the JNI node lifecycle.
4. Start the native node from a background coroutine.
5. Publish state through `StateFlow`.
6. Expose Stop and Pause actions in its notification.
7. Return `START_NOT_STICKY` initially.
8. Stop itself after the native node has shut down.
9. Never start from a boot receiver in the prototype.

For the prototype, evaluate Android’s `specialUse` foreground-service type because the node is a user-visible, long-running use case not obviously covered by another foreground-service category. Declare the required subtype explanation in the service manifest. Treat this as an engineering choice requiring policy review before public distribution, not as guaranteed Play Store approval.

Suggested explanation:

```text
Runs a user-enabled peer-to-peer Freenet node that contributes storage,
routing, and contract processing while displaying a persistent notification.
```

## Notification content

Display:

```text
Freenet node running
Mode: Local or Network
Peers: <count>
Uptime: <duration>

[Pause] [Stop]
```

## Acceptance criteria

* Starting the node creates a visible foreground-service notification.
* Leaving the Activity does not immediately stop the node.
* Turning the screen off does not immediately stop the node.
* The notification Stop action performs graceful shutdown.
* Swiping the app from Recents has documented and tested behavior.
* Android process death does not leave corrupted state.
* The app does not unexpectedly restart a node the user intentionally stopped.

---

# Phase 6: Persistent storage and identity

## Goal

Persist node state in Android-owned directories.

Use:

```text
filesDir/
  freenet/
    config/
    contracts/
    state/
    database/
    logs/

cacheDir/
  freenet/
    temporary/

noBackupFilesDir/
  freenet/
    identity/
```

## Prototype key handling

For the first local prototype:

* Use the least complex Freenet-supported file-backed key mechanism.
* Store the key material only in application-private storage.
* Never place it in external storage.
* Never include it in Android backups.
* Never print it in logs.
* Clearly label this as prototype security debt.

Before wider distribution, add Android Keystore wrapping:

1. Generate a non-exportable Android Keystore key.
2. Use it to encrypt the Freenet key-encryption material at rest.
3. Store only the encrypted blob in `noBackupFilesDir`.
4. Decrypt it only during node startup.
5. Zero temporary buffers after use.
6. Define recovery behavior when the Keystore entry is lost or invalidated.

Do not make Keystore integration a prerequisite for proving that the node runs.

## Acceptance criteria

* Node identity survives ordinary application restarts.
* Contract and database state survive restart.
* Clearing application data creates a new identity.
* No node secrets appear in logs.
* Temporary files are separated from persistent files.
* Storage use can be measured from the UI.
* Failed writes produce visible errors rather than silent corruption.

---

# Phase 7: Start a network node

## Goal

Connect the Android node to actual Freenet peers.

## Tasks

1. Add a network-mode configuration object.
2. Reuse current core defaults where possible.
3. Do not hard-code undocumented bootstrap nodes.
4. Bind management or client APIs only to loopback.
5. Start with Wi-Fi-only mode enabled.
6. Request the minimum Android permissions necessary.
7. Register a `ConnectivityManager.NetworkCallback`.
8. Notify the Rust adapter when:

   * connectivity is lost;
   * connectivity returns;
   * Wi-Fi changes;
   * the active network changes;
   * a VPN becomes active;
   * the network becomes metered.
9. Determine whether the existing transport recovers automatically.
10. Only add explicit transport restart behavior if tests prove it necessary.

Collect:

```text
Peer count
Connection attempts
Successful connections
Bytes sent
Bytes received
Current network type
Last network error
Uptime
```

Do not claim that the phone accepts inbound connections unless this is measured. Cellular carrier NAT may make the node primarily outbound-reachable.

## Acceptance criteria

* The node establishes at least one real peer connection.
* The connection remains active for at least 30 minutes on Wi-Fi.
* Temporary Wi-Fi loss does not corrupt state.
* Connectivity recovery either reconnects automatically or produces a controlled restart.
* Switching between Wi-Fi and cellular does not crash the process.
* Metered-network restrictions are respected.
* Network mode can be stopped gracefully.

## Gate 5

Do not add significant UI polish until the node has successfully maintained a real peer connection.

---

# Phase 8: Build the user interface

## Dashboard

Show:

```text
Node state
Local or network mode
Peer count
Uptime
Current network
Bytes sent and received
Storage used
Last error
Freenet core version
Android adapter version
```

Provide:

```text
Start node
Stop node
Pause node
View logs
Export diagnostic report
Open settings
```

## Settings

Initial settings:

```text
Wi-Fi only: enabled
Allow metered network: disabled
Run only while charging: optional
Maximum storage: configurable
Maximum log size: configurable
Start automatically: unavailable
Network mode: local or network
```

Do not request battery-optimization exemptions automatically. Explain the tradeoff and let the user explicitly open Android’s relevant settings screen later.

## Logs

* Keep a bounded in-memory ring buffer.
* Write rotating logs to app-private storage.
* Redact secrets and authentication material.
* Allow export of a diagnostic bundle.
* Include versions, Android build, node status, and recent sanitized logs.
* Never include identity secrets or raw databases in a normal diagnostic export.

## Acceptance criteria

* The UI remains responsive during node startup.
* Configuration survives Activity recreation.
* Errors are understandable and actionable.
* The UI accurately reflects the native node state.
* Logs cannot grow without a configured bound.

---

# Phase 9: Testing and continuous integration

## Rust tests

Add tests for:

```text
Configuration validation
State-machine transitions
Duplicate Start requests
Duplicate Stop requests
Shutdown timeouts
Error serialization
Log redaction
Path validation
```

## Android tests

Add tests for:

```text
Native library loading
JNI ping
Start/stop UI behavior
Service notification creation
Activity recreation
Service stop action
Configuration persistence
Invalid configuration handling
```

## Manual physical-device matrix

Test:

```text
App in foreground
App in background
Screen off
Device charging
Device unplugged
Wi-Fi loss
Wi-Fi restoration
Wi-Fi to cellular
Cellular to Wi-Fi
VPN enabled
Low storage
Process killed
Device rebooted
Twenty start/stop cycles
One-hour node session
Multi-hour node session
```

## CI jobs

Create jobs for:

```text
Rust formatting
Rust Clippy
Rust unit tests
ARM64 Android native cross-compilation
Android JVM tests
Android lint
Debug APK assembly
```

When `freenet-core` is modified, use its required checks:

```bash
cargo fmt
cargo clippy --all-targets -- -D warnings
cargo test
```

The Freenet agent guide and contribution policy require focused changes, regression tests for bug fixes, conventional commits, and clean formatting, linting, and tests.

---

# Phase 10: Hardening

After the MVP works, address:

```text
Android Keystore wrapping
Memory-pressure testing
Thermal testing
Doze behavior
Long-duration connectivity
Database recovery
Atomic configuration writes
Log rotation
Crash diagnostics
JNI fuzz or malformed-input testing
ABI compatibility
Dependency vulnerability review
Reproducible release builds
AGPL source distribution
Third-party notices
```

Do not implement native self-updates. Android application updates must replace the APK and packaged native library together.

Do not begin Play Store work until foreground-service policy and downloaded WASM-contract policy have received a separate review.

---

# Definition of MVP complete

The prototype is complete when all of the following are true:

* An ARM64 APK builds reproducibly.
* It installs on a physical Android phone.
* Kotlin successfully loads the Rust library.
* The Rust library embeds `freenet-core`.
* The user can explicitly start and stop the node.
* The node runs in a visible foreground service.
* A real Freenet contract executes successfully.
* The node connects to at least one real peer.
* It remains connected for at least 30 minutes on Wi-Fi.
* Its identity and state survive restart.
* Network transitions do not crash the app.
* Repeated start/stop cycles do not corrupt state.
* Logs and diagnostics can be exported safely.
* No node secrets appear in logs.
* Build and test instructions are documented.
* Every required upstream core change has its own issue and narrowly scoped patch.

---

# Required phase report format

At the end of every phase, report:

```text
Phase:
Status: PASS / BLOCKED / PARTIAL

Completed:
- ...

Files added:
- ...

Files modified:
- ...

Commands run:
- ...

Tests run:
- ...

Observed warnings:
- ...

Blockers:
- ...

Changes made to freenet-core:
- None
or
- Exact files and justification

Recommended next action:
- ...
```

Do not describe a phase as successful unless its acceptance criteria have actually been tested.

---

# First Codex assignment

For the first implementation run, complete **Phase 0 and Phase 1 only**.

Do not link `freenet-core` yet.

Deliver:

1. The companion repository structure.
2. The baseline document.
3. A buildable Compose Android application.
4. A Rust `cdylib`.
5. Working JNI ping and build-information functions.
6. ARM64 native build automation.
7. A debug APK.
8. Build and installation instructions.
9. A phase report using the required format.

Stop after JNI works and report the result. Do not begin Phase 2 in the same run.
