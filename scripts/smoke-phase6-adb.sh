#!/usr/bin/env bash
set -euo pipefail

adb_serial="${ADB_SERIAL:-}"
clear_data="${PHASE6_CLEAR_DATA:-0}"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
apk_dir="${repo_root}/artifacts/apk"
app_apk="${apk_dir}/freenet-android-node-debug.apk"
test_apk="${apk_dir}/freenet-android-node-debug-androidTest.apk"
package_name="org.freenet.androidnode"
test_component="${package_name}.test/androidx.test.runner.AndroidJUnitRunner"
test_class="${package_name}.Phase6InstrumentedTest"

if [[ -z "${adb_serial}" ]]; then
  echo "Set ADB_SERIAL to the connected Android device serial." >&2
  exit 2
fi
if [[ "${clear_data}" != "0" && "${clear_data}" != "1" ]]; then
  echo "PHASE6_CLEAR_DATA must be 0 or 1." >&2
  exit 2
fi
for apk in "${app_apk}" "${test_apk}"; do
  if [[ ! -f "${apk}" ]]; then
    echo "Missing APK: ${apk}. Run scripts/build-debug.sh in Docker first." >&2
    exit 2
  fi
done

adb_device() {
  adb -s "${adb_serial}" "$@"
}

run_identity_proof() {
  adb_device logcat -c
  adb_device shell am instrument -w -r \
    -e class "${test_class}#identityAndContractStatePersistAcrossOrdinaryRestart" \
    "${test_component}"
  adb_device logcat -d -s FreenetPhase6:I '*:S' | tail -n 1
}

latest_fingerprint() {
  adb_device logcat -d -s FreenetPhase6:I '*:S' |
    tail -n 1 |
    sed -E 's/.*identityFingerprint=([0-9a-f]+).*/\1/'
}

adb_device get-state >/dev/null
adb_device install -r "${app_apk}"
adb_device install -r "${test_apk}"

run_identity_proof
first_fingerprint="$(latest_fingerprint)"
if ! [[ "${first_fingerprint}" =~ ^[0-9a-f]{32}$ ]]; then
  echo "Could not read the first public identity fingerprint." >&2
  exit 1
fi

if [[ "${clear_data}" == "1" ]]; then
  echo "PHASE6_CLEAR_DATA=1: clearing all debug-app data to test identity reset."
  adb_device shell pm clear "${package_name}" >/dev/null
  run_identity_proof
  second_fingerprint="$(latest_fingerprint)"
  if ! [[ "${second_fingerprint}" =~ ^[0-9a-f]{32}$ ]]; then
    echo "Could not read the post-clear public identity fingerprint." >&2
    exit 1
  fi
  if [[ "${first_fingerprint}" == "${second_fingerprint}" ]]; then
    echo "Clearing app data did not create a new identity." >&2
    exit 1
  fi
  echo "Identity changed after app-data clear: ${first_fingerprint} -> ${second_fingerprint}"
fi

echo "Phase 6 private-storage, identity-continuity, contract-persistence, and write-error proof passed."
