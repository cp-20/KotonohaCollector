#!/usr/bin/env bash
set -uo pipefail

ADB_BIN="${ADB_BIN:-adb}"
PACKAGE="dev.kotonoha.collector"
IME="$PACKAGE/.CollectorImeService"
TEST_ACTIVITY="$PACKAGE/.TestPadActivity"
REMOTE_XML="/sdcard/kotonoha-ime-test.xml"
TEST_PREPARE_TELEMETRY="$PACKAGE.TEST_PREPARE_TELEMETRY"
TEST_EXPORT_TELEMETRY="$PACKAGE.TEST_EXPORT_TELEMETRY"
TELEMETRY_EXPORT="cache/kotonoha-telemetry-test.jsonl"
TELEMETRY_STATUS="cache/kotonoha-telemetry-status.txt"

PASS_COUNT=0
FAIL_COUNT=0
IME_READY=0

adb_run() {
  "$ADB_BIN" "$@"
}

editor_text() {
  adb_run shell uiautomator dump "$REMOTE_XML" >/dev/null 2>&1 || return 1
  adb_run exec-out cat "$REMOTE_XML" 2>/dev/null \
    | tr -d '\r' \
    | perl -0777 -ne 'if (/content-desc="test-editor:([^"]*)"/) { print $1; }'
}

wait_for_text() {
  local expected="$1"
  local actual=""
  local attempt
  for attempt in $(seq 1 20); do
    actual="$(editor_text)"
    if [[ "$actual" == "$expected" ]]; then
      return 0
    fi
    sleep 0.1
  done
  printf '%s' "$actual"
  return 1
}

ui_has_text() {
  local expected="$1"
  local attempt
  for attempt in $(seq 1 20); do
    adb_run shell uiautomator dump "$REMOTE_XML" >/dev/null 2>&1 || true
    if adb_run exec-out cat "$REMOTE_XML" 2>/dev/null \
      | tr -d '\r' \
      | rg -Fq "text=\"$expected\""; then
      return 0
    fi
    sleep 0.1
  done
  return 1
}

wait_for_telemetry_status() {
  local expected_prefix="$1"
  local actual=""
  local attempt
  for attempt in $(seq 1 40); do
    actual="$(adb_run exec-out run-as "$PACKAGE" cat "$TELEMETRY_STATUS" 2>/dev/null \
      | tr -d '\r' || true)"
    if [[ "$actual" == "$expected_prefix"* ]]; then
      return 0
    fi
    sleep 0.1
  done
  printf '%s' "$actual"
  return 1
}

pass_case() {
  PASS_COUNT=$((PASS_COUNT + 1))
  printf 'PASS  %s\n' "$1"
}

fail_case() {
  FAIL_COUNT=$((FAIL_COUNT + 1))
  printf 'FAIL  %s: %s\n' "$1" "$2"
}

assert_text() {
  local label="$1"
  local expected="$2"
  local actual
  if actual="$(wait_for_text "$expected")"; then
    pass_case "$label"
  else
    fail_case "$label" "expected=[$expected] actual=[$actual]"
  fi
  # uiautomator may briefly retain input focus after writing its hierarchy.
  sleep 0.18
}

fresh_pad() {
  local initial_text="${1:-}"
  local test_command="${2:-}"
  local selection_start="${3:-}"
  local selection_end="${4:-}"
  local encoded=""
  local -a start_args=(shell am start -W -n "$TEST_ACTIVITY"
    --activity-clear-top --activity-single-top)
  if [[ -n "$initial_text" ]]; then
    encoded="$(printf '%s' "$initial_text" | base64 | tr -d '\r\n')"
    start_args+=(--es initial_text_base64 "$encoded")
  fi
  if [[ -n "$selection_start" && -n "$selection_end" ]]; then
    start_args+=(--ei selection_start "$selection_start" --ei selection_end "$selection_end")
  fi
  adb_run "${start_args[@]}" >/dev/null
  # Keep the caret at the end even when the seeded line is longer than a few chars.
  adb_run shell input tap 900 525
  if [[ "$IME_READY" -eq 0 ]]; then
    local attempt
    for attempt in $(seq 1 40); do
      if adb_run logcat -d -v brief KotonohaMozc:I '*:S' 2>/dev/null \
        | tr -d '\r' | rg -q 'Mozc initialized'; then
        IME_READY=1
        break
      fi
      sleep 0.1
    done
  fi
  if [[ -n "$test_command" ]]; then
    # The first service bind may still be loading Mozc. Re-deliver the seeded
    # editor state and command only after the IME has announced readiness.
    start_args+=(--es ime_test_command "$test_command")
    adb_run "${start_args[@]}" >/dev/null
  fi
  # Gesture cases require the IME enter animation and key bounds to be stationary.
  sleep 1.2
}

tap_key() {
  adb_run shell input tap "$1" "$2"
}

flick_key() {
  adb_run shell input swipe "$1" "$2" "$3" "$4" 160
}

type_kyou() {
  flick_key 540 1810 450 1810
  flick_key 540 2074 540 2155
  tap_key 324 2208
  flick_key 324 1810 324 1720
}

type_ashita() {
  tap_key 324 1810
  flick_key 756 1810 666 1810
  tap_key 324 1942
}

require_device() {
  local state size
  state="$(adb_run get-state 2>/dev/null | tr -d '\r' || true)"
  if [[ "$state" != "device" ]]; then
    printf 'No ready Android device. Set ADB_BIN or start Pixel_10a_API_36.\n' >&2
    exit 2
  fi
  size="$(adb_run shell wm size | tr -d '\r')"
  if [[ "$size" != *"1080x2424"* ]]; then
    printf 'Expected 1080x2424 Pixel test geometry, got: %s\n' "$size" >&2
    exit 2
  fi
}

test_raw_delete() {
  fresh_pad
  tap_key 324 1810
  flick_key 324 1810 245 1810
  assert_text "raw input before delete" "あい"
  tap_key 972 1810
  assert_text "delete one composing kana" "あ"
}

test_modifier_delete() {
  fresh_pad
  tap_key 540 1810
  tap_key 324 2208
  assert_text "dakuten modifier" "が"
  tap_key 972 1810
  assert_text "delete modified kana" ""
}

test_conversion_display_and_cycle() {
  fresh_pad
  type_kyou
  assert_text "reading before conversion" "きょう"
  tap_key 972 2074
  local first second
  first="$(editor_text)"
  tap_key 972 2074
  second="$(editor_text)"
  if [[ -n "$first" && -n "$second" && "$first" != "$second" ]]; then
    pass_case "conversion key advances candidate"
  else
    fail_case "conversion key advances candidate" "first=[$first] second=[$second]"
  fi
}

test_conversion_auto_commit() {
  fresh_pad
  type_kyou
  tap_key 972 2074
  local selected
  selected="$(editor_text)"
  tap_key 756 1942
  assert_text "next kana auto-commits selected candidate" "${selected}は"
}

test_delete_during_conversion() {
  fresh_pad
  type_kyou
  tap_key 972 2074
  tap_key 972 1810
  assert_text "delete during conversion restores shortened reading" "きょ"
}

test_committed_delete_and_undo() {
  fresh_pad "abc"
  assert_text "debug pad initial text" "abc"
  tap_key 972 1810
  assert_text "delete committed character" "ab"
  tap_key 108 1810
  assert_text "undo restores deleted character" "abc"
}

test_cursor_delete() {
  fresh_pad "abc"
  tap_key 108 1942
  tap_key 972 1810
  assert_text "delete respects moved cursor" "ac"
}

test_selection_and_start_delete() {
  fresh_pad "abcdef" "dev.kotonoha.collector.TEST_DELETE_ONE" 2 5
  assert_text "delete replaces selected range" "abf"
  tap_key 108 1810
  assert_text "undo restores selected range" "abcdef"

  fresh_pad "abc" "dev.kotonoha.collector.TEST_DELETE_ONE" 0 0
  assert_text "delete at document start is a no-op" "abc"
}

test_word_swipe_delete() {
  fresh_pad "abc test" "dev.kotonoha.collector.TEST_DELETE_WORD"
  assert_text "left swipe deletes previous word" "abc "
}

test_long_press_delete() {
  fresh_pad "abcdef" "dev.kotonoha.collector.TEST_REPEAT_DELETE"
  assert_text "long press repeat path deletes five characters" "a"
}

test_accelerated_long_press_delete() {
  fresh_pad "abcdefghijklmnopqrstuvwxyz1234"
  adb_run shell input swipe 972 1810 972 1810 900
  local actual
  actual="$(editor_text)"
  if [[ ${#actual} -le 20 ]]; then
    pass_case "900ms backspace hold accelerates"
  else
    fail_case "900ms backspace hold accelerates" "remaining=[$actual] length=${#actual}"
  fi
}

test_japanese_space() {
  fresh_pad
  tap_key 972 2074
  assert_text "kana mode inserts ideographic space" "　"
}

test_mozc_candidates_after_different_commit() {
  fresh_pad
  type_kyou
  tap_key 972 2074
  # Commit the highlighted conversion by tapping the candidate strip. This is
  # the path that previously left the old native preedit observable.
  tap_key 90 1685
  assert_text "candidate-strip tap commits first phrase" "今日"
  type_ashita
  assert_text "different reading follows candidate-strip commit" "今日あした"
  tap_key 972 2074
  assert_text "Mozc converts the new reading instead of the stale one" "今日明日"
}

test_emoji_cluster_delete() {
  fresh_pad "A👨‍👩‍👧‍👦"
  tap_key 972 1810
  assert_text "delete keeps ZWJ emoji atomic" "A"

  fresh_pad "A🇯🇵"
  tap_key 972 1810
  assert_text "delete keeps flag emoji atomic" "A"

  fresh_pad "A👍🏽"
  tap_key 972 1810
  assert_text "delete keeps skin-tone emoji atomic" "A"
}

test_punctuation_commit() {
  fresh_pad
  tap_key 324 1810
  tap_key 756 2208
  assert_text "punctuation commits active reading first" "あ、"
}

test_rapid_taps() {
  fresh_pad
  local index
  for index in $(seq 1 10); do
    tap_key 324 1810
  done
  assert_text "ten rapid taps are neither lost nor duplicated" "ああああああああああ"
}

test_telemetry_schema_v3() {
  fresh_pad "" "$TEST_PREPARE_TELEMETRY"
  local status telemetry
  if status="$(wait_for_telemetry_status "prepared")"; then
    pass_case "telemetry fixture is prepared"
  else
    fail_case "telemetry fixture is prepared" "status=[$status]"
    return
  fi

  # つ -> っ -> delete while composing -> あ、 -> commit. The punctuation
  # must remain in the same composition instead of committing あ eagerly.
  flick_key 324 1942 324 1852
  tap_key 324 2208
  tap_key 972 1810
  tap_key 324 1810
  tap_key 756 2208
  tap_key 972 2208
  # Delete committed 、, replace it with い, and commit again.
  tap_key 972 1810
  flick_key 324 1810 245 1810
  tap_key 972 2208
  assert_text "punctuation stays composing until enter and can be replaced" "あい"

  fresh_pad "" "$TEST_EXPORT_TELEMETRY"
  if status="$(wait_for_telemetry_status "exported:")"; then
    pass_case "telemetry fixture is exported"
  else
    fail_case "telemetry fixture is exported" "status=[$status]"
    return
  fi
  telemetry="$(adb_run exec-out run-as "$PACKAGE" cat "$TELEMETRY_EXPORT" 2>/dev/null \
    | tr -d '\r')"
  if jq -e -s '
      . as $rows
      | ([.[] | select(.edit_operation == "DELETE") | .correction_id][0]) as $composingCorrection
      | ([.[] | select(.edit_operation == "DELETE_COMMITTED") | .correction_id][0]) as $committedCorrection
      | length >= 8
      and ([.[] | keys | join(",")] | unique | length == 1)
      and all(.[]; .schema_version == 3 and .app_version == "0.16.0"
        and .engine_version != "" and .layout_version == "kotonoha-kana12-qwerty-v1")
      and any(.[]; .type == "COMPOSITION_EDIT" and .edit_operation == "INSERT"
        and .gesture_key == "た" and .gesture_direction == "UP"
        and .gesture_dy_dp < 0 and .gesture_duration_ms > 0)
      and any(.[]; .type == "COMPOSITION_EDIT" and .candidate_source == "PREDICTION"
        and (.candidates | length) > 0 and .cursor_before >= 0 and .cursor_after >= 0)
      and any(.[]; .edit_operation == "MODIFIER_CYCLE"
        and .raw_before == "つ" and .raw_after == "っ")
      and any(.[]; .edit_operation == "DELETE" and .deleted_text == "っ"
        and .raw_before == "っ" and .raw_after == "")
      and any(.[]; .type == "COMPOSITION_EDIT" and .edit_operation == "INSERT"
        and .raw_before == "あ" and .raw_after == "あ、"
        and .gesture_key == "、" and .gesture_direction == "CENTER")
      and ([.[] | select(.type == "PUNCTUATION_COMMIT")] | length == 0)
      and ($composingCorrection != "")
      and any(.[]; .correction_id == $composingCorrection
        and .edit_operation == "INSERT" and .raw_after == "あ")
      and any(.[]; .edit_operation == "DELETE_COMMITTED"
        and .committed_text == "" and .deleted_text == "、")
      and ($committedCorrection != "")
      and any(.[]; .correction_id == $committedCorrection
        and .edit_operation == "INSERT" and .raw_after == "い")
      and any(.[]; .type == "READING_COMMIT" and .commit_method == "ENTER_KEY")
    ' >/dev/null <<<"$telemetry"; then
    pass_case "schema v3 links composition, correction, candidate, cursor, version, and gesture metadata"
  else
    fail_case "schema v3 links composition, correction, candidate, cursor, version, and gesture metadata" \
      "status=[$status] rows=$(printf '%s\n' "$telemetry" | wc -l)"
  fi
}

require_device
adb_run logcat -c
adb_run shell am force-stop "$PACKAGE" >/dev/null
adb_run shell ime set "$IME" >/dev/null
printf 'Kotonoha Pixel IME regression suite\n'
# The very first bind also maps the large Mozc data file. Warm it once so gesture assertions
# never race the initial input-view animation or native engine startup.
fresh_pad
sleep 1
case "${1:-smoke}" in
  smoke)
    # Only behavior that crosses an Android framework or Mozc JNI boundary.
    test_raw_delete
    test_conversion_display_and_cycle
    test_mozc_candidates_after_different_commit
    test_accelerated_long_press_delete
    test_japanese_space
    test_rapid_taps
    test_telemetry_schema_v3
    ;;
  delete_gestures)
    test_word_swipe_delete
    test_long_press_delete
    test_accelerated_long_press_delete
    ;;
  selection_delete)
    test_selection_and_start_delete
    ;;
  v012)
    test_raw_delete
    test_mozc_candidates_after_different_commit
    test_accelerated_long_press_delete
    test_japanese_space
    ;;
  stale_reading)
    test_mozc_candidates_after_different_commit
    ;;
  telemetry)
    test_telemetry_schema_v3
    ;;
  all)
    test_raw_delete
    test_modifier_delete
    test_conversion_display_and_cycle
    test_conversion_auto_commit
    test_mozc_candidates_after_different_commit
    test_delete_during_conversion
    test_committed_delete_and_undo
    test_cursor_delete
    test_selection_and_start_delete
    test_word_swipe_delete
    test_long_press_delete
    test_accelerated_long_press_delete
    test_emoji_cluster_delete
    test_punctuation_commit
    test_japanese_space
    test_rapid_taps
    test_telemetry_schema_v3
    ;;
  *)
    printf 'Unknown group: %s (use smoke, all, telemetry, stale_reading, v012, delete_gestures, or selection_delete)\n' "$1" >&2
    exit 2
esac

printf '\nResult: %d passed, %d failed\n' "$PASS_COUNT" "$FAIL_COUNT"
if [[ "$FAIL_COUNT" -ne 0 ]]; then
  exit 1
fi
