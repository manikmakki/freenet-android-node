# Toolchain Baseline

Baseline captured: 2026-08-01

## Freenet

- Repository: `https://github.com/freenet/freenet-core.git`
- Release tag: `v0.2.117`
- Commit: `ba831f79ad55c1942618156fef58c6ec63dba72e`
- Core crate version: `0.2.117`
- Source modifications: none
- Submodules: none registered at the captured commit

The sibling checkout is mounted at `/workspace/freenet-core` during
containerized checks. Freenet's `fdev` integration tests create ignored build
artifacts there, so the baseline script enforces a clean tracked diff before
and after every run. The Android native adapter consumes the core crate through
this mounted path, while its lockfile records the selected crate version.

## Containerized build toolchain

| Tool | Pinned version |
| --- | --- |
| Java | Eclipse Temurin OpenJDK 17.0.19+10 |
| Android SDK platform | API 36 |
| Android SDK build tools | 36.0.0 |
| Android NDK | 28.2.13676358 (r28c) |
| Android command-line tools | 15859902 |
| Android Gradle Plugin | 9.2.1 |
| Gradle | 9.4.1 |
| AGP built-in Kotlin | 2.3.10 |
| Compose BOM | 2026.06.00 |
| Rust | 1.94.0 |
| Cargo | 1.94.0 |
| cargo-ndk | 4.1.2 |
| Android Rust targets | `aarch64-linux-android`, `x86_64-linux-android` |
| Freenet contract target | `wasm32-unknown-unknown` |

Android Studio is deliberately not installed in the build image. The optional
host IDE compatibility baseline is Android Studio Quail 2, 2026.1.2 Patch 1.
All authoritative builds use the command-line toolchain above.

## Host

- Host operating system: Linux Mint 22.3 (Zena), Linux
  `6.8.0-136-generic`, x86-64
- Required host build dependency: Docker Engine with Docker Compose
- Optional host test dependency: ADB and either an emulator or physical device

## Reproduction

```bash
docker compose build dev
docker compose run --rm dev scripts/print-versions.sh
docker compose run --rm dev scripts/check-freenet-baseline.sh
docker compose run --rm dev scripts/check.sh
```

The image verifies published SHA-256 checksums for the downloaded Android
command-line tools and Gradle distribution. Tool versions are Docker build
arguments with pinned defaults, so deliberate upgrades remain reviewable.
