#!/usr/bin/env bash
set -euo pipefail

test_group="${1:-smoke}"
artifact_dir="${2:?Usage: run_android_ime_ci.sh [smoke|all] ARTIFACT_DIR}"

mkdir -p "${artifact_dir}"

collect_diagnostics() {
  adb logcat -d > "${artifact_dir}/logcat.txt" 2>&1 || true
  adb shell dumpsys input_method > "${artifact_dir}/input-method.txt" 2>&1 || true
  adb exec-out cat /sdcard/kotonoha-ime-test.xml \
    > "${artifact_dir}/ui.xml" 2>/dev/null || true
}
trap collect_diagnostics EXIT

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell wm size 1080x2424
adb shell wm density 420
tools/run_pixel_ime_tests.sh "${test_group}"
