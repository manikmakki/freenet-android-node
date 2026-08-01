#!/usr/bin/env bash
set -euo pipefail

adb_serial="${ADB_SERIAL:-}"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
apk_dir="${repo_root}/artifacts/apk"
app_apk="${apk_dir}/freenet-android-node-debug.apk"
test_apk="${apk_dir}/freenet-android-node-debug-androidTest.apk"
package_name="org.freenet.androidnode"
test_component="${package_name}.test/androidx.test.runner.AndroidJUnitRunner"
phase5_class="${package_name}.Phase5InstrumentedTest"
activity_name="${package_name}/.MainActivity"
ui_dump="$(mktemp /tmp/freenet-phase5-ui.XXXXXX.xml)"

cleanup() {
  rm -f "${ui_dump}"
}
trap cleanup EXIT

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

run_test() {
  local test_name="$1"
  adb_device shell am instrument -w -r \
    -e class "${phase5_class}#${test_name}" \
    "${test_component}"
}

dump_ui() {
  adb_device shell input keyevent KEYCODE_WAKEUP
  adb_device shell wm dismiss-keyguard
  adb_device exec-out uiautomator dump /dev/tty > "${ui_dump}"
}

button_bounds() {
  local label="$1"
  rg -o "text=\"${label}\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" "${ui_dump}" |
    sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/' |
    head -n 1
}

tap_start_button() {
  local bounds=""
  adb_device shell am start -W -n "${activity_name}" >/dev/null
  for _ in {1..10}; do
    dump_ui
    bounds="$(button_bounds "Start local node" || true)"
    if [[ -n "${bounds}" ]]; then
      read -r left top right bottom <<< "${bounds}"
      if ((right > left && bottom > top && top < 2160 && bottom > 0)); then
        adb_device shell input tap "$(((left + right) / 2))" "$(((top + bottom) / 2))"
        return 0
      fi
    fi
    adb_device shell input swipe 540 1850 540 550 250
  done
  echo "Could not find the Start local node button." >&2
  return 1
}

service_is_running() {
  adb_device shell dumpsys activity services |
    rg -F 'org.freenet.androidnode/.NodeService' >/dev/null
}

wait_for_service() {
  for _ in {1..40}; do
    if service_is_running; then
      return 0
    fi
    sleep 0.25
  done
  echo "Timed out waiting for NodeService." >&2
  return 1
}

adb_device get-state >/dev/null
adb_device install -r "${app_apk}"
adb_device install -r "${test_apk}"
adb_device shell pm grant \
  "${package_name}" \
  android.permission.POST_NOTIFICATIONS \
  >/dev/null 2>&1 || true
if ! adb_device shell dumpsys package "${package_name}" |
    rg -q 'POST_NOTIFICATIONS: granted=true'; then
  echo "Notification permission is not granted." >&2
  echo "Open the app, press Start local node, approve notifications, and rerun this script." >&2
  exit 2
fi

run_test foregroundServiceSurvivesBackgroundAndNotificationActionsStopGracefully

tap_start_button
wait_for_service

adb_device shell am force-stop "${package_name}"
sleep 2
if service_is_running; then
  echo "NodeService unexpectedly restarted after force-stop." >&2
  exit 1
fi

# A second full lifecycle run proves the abruptly terminated redb-backed node
# can open, run, and stop cleanly without an automatic sticky restart.
run_test foregroundServiceSurvivesBackgroundAndNotificationActionsStopGracefully

echo "Latest Phase 5 evidence:"
adb_device logcat -d -s FreenetPhase5:I '*:S' | tail -n 3
echo "Phase 5 foreground-service, task-removal, screen-off, notification-action, and process-death proof passed."
