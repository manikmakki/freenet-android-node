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

## Phase 2 blocker template

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
