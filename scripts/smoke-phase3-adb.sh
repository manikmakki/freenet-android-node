#!/usr/bin/env bash
set -euo pipefail

adb_serial="${ADB_SERIAL:-}"
cycle_target="${PHASE3_CYCLES:-20}"
package_name="org.freenet.androidnode"
activity_name="${package_name}/.MainActivity"
ui_dump="$(mktemp /tmp/freenet-phase3-ui.XXXXXX.xml)"

cleanup() {
    rm -f "${ui_dump}"
}
trap cleanup EXIT

if [[ -z "${adb_serial}" ]]; then
    echo "Set ADB_SERIAL to the connected Android device serial." >&2
    exit 2
fi

if ! [[ "${cycle_target}" =~ ^[1-9][0-9]*$ ]]; then
    echo "PHASE3_CYCLES must be a positive integer." >&2
    exit 2
fi

adb_device() {
    adb -s "${adb_serial}" "$@"
}

dump_ui() {
    adb_device shell input keyevent KEYCODE_WAKEUP
    adb_device shell wm dismiss-keyguard
    adb_device exec-out uiautomator dump /dev/tty > "${ui_dump}"
}

screen_contains() {
    local expected="$1"
    rg -Fq "text=\"${expected}\"" "${ui_dump}"
}

scroll_to_top() {
    for _ in {1..8}; do
        adb_device shell input swipe 540 550 540 1850 120
    done
}

wait_for_text() {
    local expected="$1"
    local attempts="${2:-30}"

    scroll_to_top
    for ((attempt = 1; attempt <= attempts; attempt++)); do
        dump_ui
        if screen_contains "${expected}"; then
            return 0
        fi
        sleep 0.25
    done

    echo "Timed out waiting for UI text: ${expected}" >&2
    rg -o 'text="[^"]*"' "${ui_dump}" >&2 || true
    return 1
}

wait_for_fragment() {
    local expected="$1"
    local attempts="${2:-10}"

    for ((attempt = 1; attempt <= attempts; attempt++)); do
        scroll_to_top
        for _ in {1..8}; do
            dump_ui
            if rg -Fq "${expected}" "${ui_dump}"; then
                return 0
            fi
            adb_device shell input swipe 540 1850 540 550 180
        done
        sleep 0.25
    done

    echo "Timed out waiting for UI fragment: ${expected}" >&2
    return 1
}

button_bounds() {
    local label="$1"
    rg -o "text=\"${label}\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" "${ui_dump}" |
        sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/' |
        head -n 1
}

tap_button() {
    local label="$1"
    local bounds=""

    for _ in {1..8}; do
        dump_ui
        bounds="$(button_bounds "${label}" || true)"
        if [[ -n "${bounds}" ]]; then
            read -r left top right bottom <<< "${bounds}"
            if ((right > left && bottom > top && top < 2160 && bottom > 0)); then
                adb_device shell input tap "$(((left + right) / 2))" "$(((top + bottom) / 2))"
                return 0
            fi
        fi
        adb_device shell input swipe 540 1850 540 550 250
    done

    echo "Could not find a visible '${label}' button." >&2
    return 1
}

adb_device get-state >/dev/null
adb_device shell am force-stop "${package_name}"
adb_device shell input keyevent KEYCODE_WAKEUP
adb_device shell wm dismiss-keyguard
adb_device shell am start -W -n "${activity_name}" >/dev/null
wait_for_text "Node state: Stopped" 20

echo "Checking controlled duplicate Stop response..."
tap_button "Stop node"
wait_for_text "Node state: Stopped" 10
wait_for_fragment 'Last lifecycle response: {"ok":true' 10

for ((cycle = 1; cycle <= cycle_target; cycle++)); do
    tap_button "Start local node"
    wait_for_text "Node state: RunningLocal" 40

    if ((cycle == 1)); then
        tap_button "Start local node"
        wait_for_text "Node state: RunningLocal" 10
        wait_for_fragment "NODE_ALREADY_RUNNING" 10
    fi

    tap_button "Stop node"
    wait_for_text "Node state: Stopped" 40
    echo "Completed on-device start/stop cycle ${cycle}/${cycle_target}"
done

wait_for_text "Completed start cycles: ${cycle_target}" 10
echo "Phase 3 ADB soak passed: ${cycle_target} start/stop cycles."
