# Freenet Android Node

An engineering prototype for embedding Freenet in an Android application. The
current Phase 8 implementation provides a minimal Android shell around Freenet
core 0.2.117: the existing core dashboard in a locked-down WebView, a hamburger
drawer for node controls and policy settings, and copyable JSON diagnostics.
The client/dashboard API remains loopback-only on port 7509, while network mode
discovers peers from Freenet core's documented gateway index.

## Prerequisites

- Docker Engine with Docker Compose
- A sibling `freenet-core` checkout for the local native dependency and Phase 0
  baseline checks
- Optional: host-side ADB or Android Studio for installing the exported APK

The compiler, Android SDK/NDK, Rust, Cargo, Gradle, and Java toolchains are
installed in the project image. No host Android or Rust toolchain is required.

Expected workspace layout:

```text
workspace/
├── freenet-core/
└── freenet-android-node/
```

## Build

Build the image once:

```bash
docker compose build dev
```

Build both Android native libraries and the debug APK:

```bash
docker compose run --rm dev scripts/build-debug.sh
```

The build writes host-visible artifacts to:

```text
artifacts/
├── apk/
│   ├── freenet-android-node-debug.apk
│   └── freenet-android-node-debug-androidTest.apk
└── native/
    ├── arm64-v8a/libfreenet_android.so
    └── x86_64/libfreenet_android.so
```

Cargo and Gradle caches live in Docker named volumes. Android's debug-only
keystore is kept under the persistent Gradle cache so APKs from successive
one-shot containers can update each other. Intermediate output is not copied
into `artifacts/`.

Verify the exported files from the host:

```bash
(cd artifacts && sha256sum -c SHA256SUMS.txt)
```

## Checks

Run the formatting, lint, unit-test, Android lint, and assembly checks:

```bash
docker compose run --rm dev scripts/check.sh
```

Run the tracked-clean sibling Freenet Phase 0 baseline checks separately:

```bash
docker compose run --rm dev scripts/check-freenet-baseline.sh
```

The Freenet baseline is intentionally separate because its full test and Clippy
suites are substantially slower than the Android adapter checks.

## Install and exercise the local node

Use a host ADB session so Docker does not need USB access:

```bash
adb install -r artifacts/apk/freenet-android-node-debug.apk
adb shell am start -n org.freenet.androidnode/.MainActivity
```

Press **Start local node** and wait for `RunningLocal`; press **Stop node** and
wait for `Stopped`. The Activity polls a structured native status envelope.
Persistent node data uses `filesDir`, disposable caches use `cacheDir`, and
identity material uses `noBackupFilesDir`.

On first launch, the app presents a non-dismissible alpha-risk disclaimer. The
checkbox must be selected before **Accept and continue** is enabled. Acceptance
is stored for the installed Android `versionCode`; incrementing `versionCode`
for a later release requires acceptance again. Clearing application data also
clears acceptance. Boot restoration and direct foreground-service starts are
blocked until the disclaimer for the current version has been accepted.

The Android node uses Freenet's conventional client API port, `7509`. To open
the running node's dashboard from the development host, forward that port over
ADB and visit `http://127.0.0.1:7509/`:

```bash
adb forward tcp:7509 tcp:7509
```

The dashboard is available in local mode, but resources hosted by other nodes
remain unavailable until the Android node is running in network mode and has
connected peers.

## Embedded dashboard and diagnostics

While the node runs, the app displays Freenet core's own dashboard from
`http://127.0.0.1:7509/` as its primary content. Android cleartext networking is
disabled globally and enabled only for that IPv4 loopback host. WebView
navigation remains restricted to the loopback origin, exposes no JavaScript-to-
Android bridge, disables file/content access and popups, and allows only the
dashboard's exact HTTPS logo asset as an external subresource.

User-clicked hosted-app links under `/v1/contract/web/` open in Android's
default browser, where those apps are not constrained by the embedded
dashboard's narrow subresource allowlist. The WebView remains on the dashboard;
dashboard detail routes stay embedded, and redirects or subresources cannot
launch external applications without a user gesture.

The top-left hamburger opens all Android-owned controls so the rest of the
screen remains available to the core dashboard. The drawer contains node
Start/Pause/Resume/Stop controls, the power and network-data policies, the local
developer start, and navigation to diagnostics. It opens only from the
hamburger, not an edge swipe. The Android shell follows the system light/dark
theme; Back closes an open drawer first, then returns diagnostics to Dashboard.

The **For nerds** view refreshes a formatted JSON snapshot containing node
status and metrics, adapter/core versions, and up to 128 entries from the
bounded 256-entry sanitized native log ring. **Copy JSON** places the whole
snapshot on the Android clipboard for issue reports. Identity secrets and raw
databases are never included.

## Connect to the Freenet network

Press **Start network node** to join the network. The default is deliberately
fail-closed: Android's active default network must provide validated Internet
access and report `NET_CAPABILITY_NOT_METERED`. Transport type is not used as a
cost proxy, so unmetered Wi-Fi, Ethernet, cellular, or VPN networks are eligible
and metered networks of any type are blocked. The user may instead select **Any
validated network**. If the selected policy becomes ineligible while the node
is running, the service performs a graceful shutdown.

The app registers a `ConnectivityManager.NetworkCallback` and forwards default
network availability, validation, Wi-Fi, metered, VPN, and active-network
changes to Rust. Status includes current peer count, adapter-observed connection
attempt epochs, successful connections, bytes sent/received, current network
type, last network error, and uptime. Peer count and traffic come from Freenet
core's live transport counters. The prototype does not claim inbound
reachability; an Android phone behind carrier or Wi-Fi NAT may be primarily
outbound-reachable.

Run the quick real-network proof from the host:

```bash
ADB_SERIAL=<device-serial> scripts/smoke-phase7-adb.sh
```

This first proves that a synthetic cellular configuration is rejected, then
uses the device's real unmetered Wi-Fi to establish at least one Freenet
connection, observe bidirectional traffic, and stop gracefully. Run the
30-minute continuous-peer gate explicitly with:

```bash
ADB_SERIAL=<device-serial> PHASE7_STABILITY=1 scripts/smoke-phase7-adb.sh
```

Neither command changes the device's Wi-Fi or cellular settings. Network-loss
and transport-switch testing therefore requires a separately authorized manual
or ADB network change while the node is running.

The node lifecycle is owned by `NodeService`, an Android foreground service.
**Node runs when** defaults to **Manual**; **Charging** runs only while Android
reports the device is charging, and **Always** runs whenever the network policy
is eligible. Automatic modes keep a lightweight foreground controller and
notification alive while waiting for power or network conditions. The selected
power and network policies survive process and device restarts.

Pause performs a graceful native shutdown and suspends the selected schedule
until explicit Resume. Stop also performs a graceful shutdown and returns the
power policy to Manual. Automatic restart is best-effort: the service is sticky
in automatic modes and a boot/package-update receiver restores it, but Android
force-stop, OEM background restrictions, and platform foreground-service limits
can still prevent restart. The app does not request a battery-optimization
exemption.

Removing the Activity from Recents does not stop the foreground service. The
persistent notification reports running/waiting/paused state and provides
Pause/Resume and Stop actions. The `specialUse` foreground-service declaration
is an engineering choice for this user-visible peer-to-peer workload and still
requires distribution policy review before any public-store release.

Freenet core currently exposes only a limited aggregate outbound bandwidth
hook, not reliable independent send and receive policy controls. Separate
upload/download restrictions are therefore intentionally deferred rather than
presented as controls the adapter cannot enforce.

Run the repeatable Phase 3 physical-device soak from the host:

```bash
ADB_SERIAL=<device-serial> PHASE3_CYCLES=20 scripts/smoke-phase3-adb.sh
```

The script leaves the node stopped. It verifies controlled duplicate Stop and
Start behavior, then waits for `RunningLocal` and `Stopped` on every cycle.

Run the repeatable Phase 5 physical-device lifecycle proof from the host:

```bash
ADB_SERIAL=<device-serial> scripts/smoke-phase5-adb.sh
```

This verifies the foreground notification and its Pause/Stop actions, removes
the Activity task, turns the screen off, checks non-sticky behavior, force-stops
the process while the node is live, and then proves a clean explicit restart.

## Persistent storage and prototype identity security

Phase 6 separates Android-owned storage by durability and sensitivity:

```text
filesDir/freenet/          persistent database, contracts, state, config, logs
cacheDir/freenet/temporary/  disposable web-app and Wasmtime caches
noBackupFilesDir/freenet/identity/  transport keypair and delegate cipher
```

Rust exclusively creates and loads the identity files with owner-only
permissions. Kotlin receives only a public-key-derived fingerprint and
aggregate byte counts. The UI labels the current file-backed protection as
prototype security debt: Android Keystore wrapping, invalidation recovery, and
explicit temporary-buffer hardening remain required before wider distribution.
Android backups remain disabled for the entire application.

Run the non-destructive Phase 6 device proof with:

```bash
ADB_SERIAL=<device-serial> scripts/smoke-phase6-adb.sh
```

To additionally prove that clearing application data creates a new identity,
opt in to deleting all debug-app state:

```bash
ADB_SERIAL=<device-serial> PHASE6_CLEAR_DATA=1 scripts/smoke-phase6-adb.sh
```

## Run the Phase 4 contract proof

The app exposes **Run WASM contract proof** and **Verify contract persistence**
for manual testing. The reproducible gate uses Android instrumentation so it
does not depend on screen, keyguard, or Compose scroll state:

```bash
ADB_SERIAL=<device-serial> scripts/smoke-phase4-adb.sh
```

The script installs the checksum-tracked main and instrumentation APKs, runs a
PUT/GET/UPDATE/GET round-trip with Freenet core's existing
`test-contract-mock-aligned` fixture, restarts the node, verifies the exact
updated state, prints timing and peak-RSS evidence, and leaves the node stopped.

Rebuild the packaged upstream fixture from the recorded Freenet baseline with:

```bash
docker compose run --rm dev scripts/prepare-contract-fixture.sh
```

## Freenet feature selection

The adapter disables Freenet's implicit defaults and selects the required set
explicitly: `redb`, `trace`, `wasmtime-backend`, and `websocket`. This preserves
the current upstream default functionality while making Android builds
auditable. Freenet 0.2.117 does not compile with tracing omitted because several
unconditionally compiled modules use tracing-gated APIs, so `trace` is required
in addition to the Phase 2 A-E matrix.

For an x86-64 emulator, Android packages the x86-64 library. A physical ARM64
device selects the arm64-v8a library from the same APK.

## Useful commands

Open a shell in the development environment:

```bash
docker compose run --rm dev bash
```

Rebuild only the Rust native libraries:

```bash
docker compose run --rm dev scripts/build-native.sh
```

Assemble Android after the native libraries already exist:

```bash
docker compose run --rm dev android/gradlew -p android :app:assembleDebug
```

See [`docs/BASELINE.md`](docs/BASELINE.md),
[`docs/PORTING_NOTES.md`](docs/PORTING_NOTES.md), and the architecture decision
record in [`docs/adr/0001-embed-freenet-with-jni.md`](docs/adr/0001-embed-freenet-with-jni.md).
The gate results are in [`docs/PHASE_0_1_REPORT.md`](docs/PHASE_0_1_REPORT.md),
[`docs/PHASE_2_REPORT.md`](docs/PHASE_2_REPORT.md), and
[`docs/PHASE_3_REPORT.md`](docs/PHASE_3_REPORT.md), and
[`docs/PHASE_4_REPORT.md`](docs/PHASE_4_REPORT.md).
