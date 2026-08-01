# Freenet Android Node

An engineering prototype for embedding Freenet in an Android application. The
current implementation is intentionally limited to the Phase 1 JNI bridge: it
does **not** link or start `freenet-core` yet.

## Prerequisites

- Docker Engine with Docker Compose
- A sibling `freenet-core` checkout for the Phase 0 baseline checks
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
├── apk/freenet-android-node-debug.apk
└── native/
    ├── arm64-v8a/libfreenet_android.so
    └── x86_64/libfreenet_android.so
```

Cargo and Gradle caches live in Docker named volumes. Intermediate output is
not copied into `artifacts/`.

Verify the exported files from the host:

```bash
(cd artifacts && sha256sum -c SHA256SUMS.txt)
```

## Checks

Run the Phase 1 formatting, lint, unit-test, Android lint, and assembly checks:

```bash
docker compose run --rm dev scripts/check.sh
```

Run the tracked-clean sibling Freenet Phase 0 baseline checks separately:

```bash
docker compose run --rm dev scripts/check-freenet-baseline.sh
```

The Freenet baseline is intentionally separate because its full test and Clippy
suites are substantially slower than the Android adapter checks.

## Install and exercise JNI

Use a host ADB session so Docker does not need USB access:

```bash
adb install -r artifacts/apk/freenet-android-node-debug.apk
adb shell am start -n org.freenet.androidnode/.MainActivity
```

The screen should report `Native bridge: Loaded`. Press **Run native test** and
verify the result is `pong`. The native build information should identify the
ABI selected by Android.

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
The current gate results are in
[`docs/PHASE_0_1_REPORT.md`](docs/PHASE_0_1_REPORT.md).
