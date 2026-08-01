# ADR 0001: Embed Freenet with JNI

- Status: Accepted
- Date: 2026-08-01

## Context

The Android application needs to own a Freenet node for longer than an Activity
lifetime, store its state in application-private directories, and shut it down
gracefully. Starting the desktop CLI as a child process would conflict with the
Android application lifecycle, assume desktop process and signal behavior, and
make status/error handling unnecessarily indirect.

## Decision

Compile an Android-specific Rust `cdylib` and load it from Kotlin through a
narrow JNI boundary. The adapter will eventually call `freenet-core` library
entry points directly. Kotlin will pass explicit configuration and paths, while
Rust owns native runtime state. JNI values remain simple strings and serialized
envelopes; Kotlin never receives Rust pointers or executor handles.

Phase 1 proves only library loading and two static diagnostic calls. It does not
link Freenet.

## Consequences

- The APK and native library are versioned and updated together.
- Rust panics and errors must be contained at the JNI boundary.
- Android lifecycle and foreground-service behavior remain in Kotlin.
- Freenet portability issues can usually be isolated in the Android adapter.
- Native code must be cross-compiled for every packaged Android ABI.
