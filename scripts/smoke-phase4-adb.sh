#!/usr/bin/env bash
set -euo pipefail

adb_serial="${ADB_SERIAL:-}"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
apk_dir="${repo_root}/artifacts/apk"
app_apk="${apk_dir}/freenet-android-node-debug.apk"
test_apk="${apk_dir}/freenet-android-node-debug-androidTest.apk"
test_component="org.freenet.androidnode.test/androidx.test.runner.AndroidJUnitRunner"

if [[ -z "${adb_serial}" ]]; then
  echo "Set ADB_SERIAL to the connected Android device serial." >&2
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

adb_device get-state >/dev/null
adb_device install -r "${app_apk}"
adb_device install -r "${test_apk}"
adb_device shell am instrument -w -r \
  -e class org.freenet.androidnode.Phase4InstrumentedTest \
  "${test_component}"

echo "Latest Phase 4 metrics:"
adb_device logcat -d -s FreenetPhase4:I '*:S' | tail -n 1
echo "Phase 4 instrumentation proof passed and left the node stopped."
