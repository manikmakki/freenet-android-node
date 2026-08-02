#!/usr/bin/env bash
set -euo pipefail

adb_serial="${ADB_SERIAL:-}"
stability="${PHASE7_STABILITY:-0}"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
apk_dir="${repo_root}/artifacts/apk"
app_apk="${apk_dir}/freenet-android-node-debug.apk"
test_apk="${apk_dir}/freenet-android-node-debug-androidTest.apk"
package_name="org.freenet.androidnode"
test_component="${package_name}.test/androidx.test.runner.AndroidJUnitRunner"
test_class="${package_name}.Phase7InstrumentedTest"

if [[ -z "${adb_serial}" ]]; then
  echo "Set ADB_SERIAL to the connected Android device serial." >&2
  exit 2
fi
if [[ "${stability}" != "0" && "${stability}" != "1" ]]; then
  echo "PHASE7_STABILITY must be 0 or 1." >&2
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

run_instrumentation() {
  local method="$1"
  local output
  output="$(adb_device shell am instrument -w -r \
    -e class "${test_class}#${method}" \
    "${test_component}")"
  printf '%s\n' "${output}"
  if [[ "${output}" != *"OK (1 test)"* ]] || [[ "${output}" == *"FAILURES!!!"* ]]; then
    echo "Phase 7 instrumentation failed: ${method}" >&2
    return 1
  fi
}

adb_device get-state >/dev/null
adb_device install -r "${app_apk}"
adb_device install -r "${test_apk}"

run_instrumentation "rejectsCellularBeforeStartingNativeNetworkNode"
run_instrumentation "connectsToDocumentedNetworkPeerAndStopsGracefully"

if [[ "${stability}" == "1" ]]; then
  run_instrumentation "maintainsPeerForThirtyMinutesOnWifi"
fi

adb_device logcat -d -s FreenetPhase7:I '*:S' | tail -n 4
echo "Phase 7 policy, real-peer connection, metrics, and graceful-stop proof passed."
