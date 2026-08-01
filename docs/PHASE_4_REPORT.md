# Phase 4 Report

Phase: Prove WASM contract execution on Android
Status: PASS

Completed:
- Reused Freenet core's existing `test-contract-mock-aligned` Wasmtime
  conformance fixture rather than defining an Android-specific contract.
- Added a Docker-only fixture preparation script with a pinned SHA-256 check.
- Added asynchronous structured JNI commands and status for a bounded
  contract round-trip and post-restart persistence verification.
- Used Freenet stdlib's `WebApi` over the local node's supported native
  WebSocket protocol, matching the request path used by `fdev`.
- Executed PUT, initial-state GET, full-state UPDATE, and updated-state GET.
- Stopped and restarted the embedded node, reopened redb, and verified the
  exact updated bytes with a new WebSocket connection.
- Added manual Compose controls plus an Android instrumentation test that is
  independent of the screen/keyguard and guarantees cleanup.
- Exported and checksum-tracked both the main and instrumentation APKs.

Fixture provenance:
- Source: `freenet-core/tests/test-contract-mock-aligned`
- Freenet commit: `7f1f83875fd76a8ceb9d12270975e8735f4659cd`
- Compiled size: 127,707 bytes
- SHA-256: `fed7e208fb711822f060f030f856e97580cd9b1fc02f79e7a6dcac690bbc982c`

Physical-device result:

| Measurement | Result |
| --- | ---: |
| Device | OnePlus IN2017, ARM64, Android 13/API 33 |
| Contract load/key creation | 1,877 us |
| First execution | 125,135 us |
| Subsequent execution | 26,763 us |
| Post-restart persisted GET | 2,850 us |
| Peak resident set | 195,716 KiB (about 191 MiB) |
| Result | `phase4-updated-state` |
| Persistence | Verified after clean node restart |
| Instrumentation duration | 0.938 seconds |

Commands run:
- `docker compose run --rm dev scripts/prepare-contract-fixture.sh`
- `docker compose run --rm dev scripts/check.sh`
- `docker compose run --rm dev scripts/build-debug.sh`
- `(cd artifacts && sha256sum -c SHA256SUMS.txt)`
- `ADB_SERIAL=196ffc6e scripts/smoke-phase4-adb.sh`
- Host ADB app-private redb and instrumentation-process logcat inspections

Tests run:
- ShellCheck, Rust formatting, and Clippy with warnings denied
- Ten native unit tests, including fixture/oracle and lifecycle guard tests
- ARM64 and x86-64 release native builds
- Android unit-test task, lint, main APK assembly, and instrumentation APK
  assembly
- On-device AndroidJUnitRunner contract execution/restart test: one passed,
  zero failed, zero ignored
- Final redb presence, process-exit, and crash/ANR/warning review

Warnings and Gate 4 classification:
- Compilation: pass.
- Dynamic linking: pass; no Android linker error.
- Executable-memory policy: pass; Wasmtime/Cranelift executed the fixture.
- Wasmtime initialization: pass; no Wasmtime warning.
- Contract loading: pass.
- Filesystem access: pass; redb persisted under `noBackupFilesDir`.
- Memory exhaustion: pass; peak RSS was about 191 MiB.
- Freenet-specific behavior: pass; PUT/GET/UPDATE/GET and restart persistence
  returned the exact expected state.
- Android warns that the instrumentation APK has no ABI while its target APK
  is ARM64. This is expected: native code belongs to and loaded from the target
  APK. Oplus property/runtime-flag and missing debug-profile `.dm` warnings are
  unrelated device diagnostics.

Blockers:
- None. Gate 4 is satisfied on physical ARM64 Android.

Changes made to freenet-core:
- None; the sibling checkout remains tracked-clean.

Recommended next action:
- Begin Phase 5 in a separate run. Do not begin foreground-service or network
  mode work as part of this phase.
