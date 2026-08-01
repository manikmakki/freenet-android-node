# Android Porting Notes

This is a running record of Android compilation and runtime findings.

## Phase 0 and Phase 1

- `freenet-core` is intentionally not linked.
- The JNI adapter builds independently for `aarch64-linux-android` and
  `x86_64-linux-android` with Android API 28 as its minimum native platform.
- No Freenet source files or platform conditionals were added.
- The Phase 1 APK installs and cold-launches on a OnePlus IN2017 running Android
  13 (API 33). Android selects `arm64-v8a`; Kotlin loads the native library; the
  build-info call reports `aarch64-linux-android`; and JNI ping returns `pong`.
- The x86-64 library is packaged and statically verified, but emulator runtime
  coverage remains optional and has not yet been run.

## Phase 2 feature selection

The adapter depends on the sibling `freenet` crate with
`default-features = false`, then explicitly enables `redb`, `trace`,
`wasmtime-backend`, and `websocket`. This is the same functional set as the
current upstream defaults. Keeping the list explicit documents which core
subsystems enter the Android library and makes future feature changes visible.

The requested A-E feature matrix was compiled for `aarch64-linux-android` at
API 28. A-E expose unsupported feature compositions in Freenet 0.2.116; adding
the upstream-default `trace` feature to E produces a successful Android shared
library.

| Case | Freenet features | Result |
| --- | --- | --- |
| A | none | Fails: WASM backend, storage, WebSocket, and tracing assumptions |
| B | `redb` | Fails: WASM backend, WebSocket, and tracing assumptions |
| C | `wasmtime-backend` | Fails: storage, WebSocket, and tracing assumptions |
| D | `websocket` | Fails: WASM backend, storage, and tracing assumptions |
| E | `redb`, `wasmtime-backend`, `websocket` | Fails: tracing assumptions |
| Required set | E plus `trace` | Passes |

These failures are not Android-specific. They occur while compiling Freenet's
own modules because the crate currently declares features optional that its
unconditionally compiled code requires.

## Phase 2 reproduced blockers

```text
Dependency or module: freenet::wasm_runtime::engine
Android target: aarch64-linux-android (API 28)
Feature combination: A, B, and D (wasmtime-backend omitted)
Exact compiler or linker error: compile_error!("The wasmtime-backend feature must be enabled.")
Likely platform assumption: Freenet currently has no non-Wasmtime engine implementation.
Can it be solved in the adapter? Yes; enable freenet/wasmtime-backend.
Would it require a core change? Only if Freenet intends no-backend builds to be supported.
Smallest proposed solution: Keep wasmtime-backend in the adapter's required feature set.
Relevant upstream issue: None filed; an issue is appropriate only if no-backend builds should be supported.
```

```text
Dependency or module: freenet::contract::storages
Android target: aarch64-linux-android (API 28)
Feature combination: A, C, and D (redb and sqlite omitted)
Exact compiler or linker error: unresolved import `super::storages::Storage`; `Storage` is cfg-gated behind sqlite or redb.
Likely platform assumption: Core contract code assumes one persistent storage backend is selected.
Can it be solved in the adapter? Yes; enable freenet/redb.
Would it require a core change? Only if storage-free builds are intended to compile.
Smallest proposed solution: Keep redb in the adapter's required feature set.
Relevant upstream issue: None filed; the feature dependency could be validated more directly upstream.
```

```text
Dependency or module: freenet::server
Android target: aarch64-linux-android (API 28)
Feature combination: A, B, and C (websocket omitted)
Exact compiler or linker error: unresolved imports of `crate::server`; the module is cfg-gated behind websocket.
Likely platform assumption: Unconditionally compiled node/configuration paths assume the WebSocket server exists.
Can it be solved in the adapter? Yes; enable freenet/websocket.
Would it require a core change? Only if a server-free library configuration is intended to compile.
Smallest proposed solution: Keep websocket in the adapter's required feature set.
Relevant upstream issue: None filed; the feature dependency could be made explicit upstream.
```

```text
Dependency or module: freenet tracing and generated topology modules
Android target: aarch64-linux-android (API 28)
Feature combination: E (redb + wasmtime-backend + websocket, trace omitted)
Exact compiler or linker error: unresolved import `crate::tracing::tracer`; unresolved import `topology_generated`; and no method named `json` on tracing_subscriber's fmt layer.
Likely platform assumption: Core config, generated-module re-exports, and test utilities assume trace-gated modules/dependency features are present.
Can it be solved in the adapter? Yes; enable freenet/trace.
Would it require a core change? Yes, if trace is meant to remain independently optional.
Smallest proposed solution: Preserve upstream's trace default in the adapter; upstream can cfg-gate all trace-only imports and calls for a genuinely optional feature.
Relevant upstream issue: None filed; recommend an upstream feature-composition issue before relying on trace-free builds.
```

## Phase 2 Android result

With the required feature set, Wasmtime/Cranelift, redb, WebSockets, tracing,
Freenet, and the JNI adapter compile and link without Android-specific source
patches. No keyring, signal, process-spawning, hostname, notification, socket,
or C/C++ compilation failure was reproduced in this phase. Runtime use of
those subsystems remains untested until later phases actually start a node.

## Blocker template

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
