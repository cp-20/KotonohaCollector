#!/usr/bin/env bash
set -uo pipefail

ADB_BIN="${ADB_BIN:-adb}"
APP_ID="dev.cp20.kotonoha"
CODE_PACKAGE="dev.kotonoha.collector"
IME="$APP_ID/$CODE_PACKAGE.CollectorImeService"
TEST_ACTIVITY="$APP_ID/$CODE_PACKAGE.TestPadActivity"
REMOTE_XML="/sdcard/kotonoha-ime-test.xml"
TEST_PREPARE_TELEMETRY="$CODE_PACKAGE.TEST_PREPARE_TELEMETRY"
TEST_EXPORT_TELEMETRY="$CODE_PACKAGE.TEST_EXPORT_TELEMETRY"
TEST_SET_SELECTION="$CODE_PACKAGE.TEST_SET_SELECTION"
TEST_PREPARE_PARTIAL_CONVERSION="$CODE_PACKAGE.TEST_PREPARE_PARTIAL_CONVERSION"
TEST_COMMIT_PARTIAL_CONVERSION="$CODE_PACKAGE.TEST_COMMIT_PARTIAL_CONVERSION"
EXPECTED_DENSITY="${EXPECTED_DENSITY:-420}"
TELEMETRY_EXPORT="cache/kotonoha-telemetry-test.jsonl"
TELEMETRY_STATUS="cache/kotonoha-telemetry-status.txt"

PASS_COUNT=0
FAIL_COUNT=0
IME_READY=0
INSTALLED_APP_VERSION=""

adb_run() {
  "$ADB_BIN" "$@"
}

select_test_ime() {
  local attempt
  local selected=""
  adb_run shell ime enable "$IME" >/dev/null
  adb_run shell ime set "$IME" >/dev/null
  for attempt in $(seq 1 20); do
    selected="$(adb_run shell settings get secure default_input_method 2>/dev/null \
      | tr -d '\r')"
    if [[ "$selected" == "$IME" ]]; then
      return 0
    fi
    sleep 0.25
  done
  printf 'Failed to select test IME: expected=%s actual=%s\n' "$IME" "$selected" >&2
  exit 2
}

editor_text() {
  adb_run shell uiautomator dump "$REMOTE_XML" >/dev/null 2>&1 || return 1
  adb_run exec-out cat "$REMOTE_XML" 2>/dev/null \
    | tr -d '\r' \
    | perl -0777 -ne 'if (/content-desc="test-editor:([^"]*?);composing:-?\d+:-?\d+"/) { print $1; }'
}

composition_range() {
  adb_run shell uiautomator dump "$REMOTE_XML" >/dev/null 2>&1 || return 1
  adb_run exec-out cat "$REMOTE_XML" 2>/dev/null \
    | tr -d '\r' \
    | perl -0777 -ne \
      'if (/content-desc="test-editor:[^"]*;composing:(-?\d+):(-?\d+)"/) { print "$1:$2"; }'
}

ime_cursor_position() {
  adb_run shell dumpsys input_method 2>/dev/null \
    | tr -d '\r' \
    | perl -ne '$position = $1 if /mCursorSelStart=(\d+)/; END { print $position; }'
}

ime_editor_has_focus() {
  adb_run shell dumpsys input_method 2>/dev/null \
    | tr -d '\r' \
    | perl -ne '$found = 1 if /mServedView=android\.widget\.EditText/; END { exit($found ? 0 : 1); }'
}

require_ime_window_visible() {
  local attempt
  local selected=""
  local visibility=""
  for attempt in $(seq 1 40); do
    selected="$(adb_run shell settings get secure default_input_method 2>/dev/null \
      | tr -d '\r')"
    visibility="$(adb_run shell dumpsys input_method 2>/dev/null \
      | tr -d '\r' \
      | sed -nE 's/.*mImeWindowVis=([0-9]+).*/\1/p' \
      | tail -n 1)"
    if [[ "$selected" == "$IME" \
      && "$visibility" =~ ^[0-9]+$ ]] \
      && (( (visibility & 2) != 0 )); then
      return 0
    fi
    sleep 0.25
  done
  printf 'Test IME did not become visible (selected=%s mImeWindowVis=%s).\n' \
    "$selected" "$visibility" >&2
  exit 2
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

wait_for_composition() {
  local expected="$1"
  local actual=""
  local attempt
  for attempt in $(seq 1 20); do
    actual="$(composition_range)"
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
      | grep -Fq "text=\"$expected\""; then
      return 0
    fi
    sleep 0.1
  done
  return 1
}

require_host_tools() {
  local tool
  if ! command -v "$ADB_BIN" >/dev/null 2>&1; then
    printf 'Required ADB command is not installed: %s\n' "$ADB_BIN" >&2
    exit 2
  fi
  for tool in base64 grep head jq perl sed seq tail tr; do
    if ! command -v "$tool" >/dev/null 2>&1; then
      printf 'Required host command is not installed: %s\n' "$tool" >&2
      exit 2
    fi
  done
}

wait_for_telemetry_status() {
  local expected_prefix="$1"
  local actual=""
  local attempt
  for attempt in $(seq 1 40); do
    actual="$(adb_run exec-out run-as "$APP_ID" cat "$TELEMETRY_STATUS" 2>/dev/null \
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

assert_composition() {
  local label="$1"
  local expected="$2"
  local actual
  if actual="$(wait_for_composition "$expected")"; then
    pass_case "$label"
  else
    fail_case "$label" "expected=[$expected] actual=[$actual]"
  fi
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
  select_test_ime
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
        | tr -d '\r' | grep -q 'Mozc initialized'; then
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
  sleep 0.1
}

press_key() {
  adb_run shell input motionevent DOWN "$1" "$2"
  sleep 0.08
  adb_run shell input motionevent UP "$1" "$2"
  sleep 0.1
}

flick_key() {
  adb_run shell input swipe "$1" "$2" "$3" "$4" 160
}

flick_hold_key() {
  adb_run shell input motionevent DOWN "$1" "$2"
  adb_run shell input motionevent MOVE "$3" "$4"
  sleep 0.9
  adb_run shell input motionevent UP "$3" "$4"
  sleep 0.2
}

hold_key() {
  adb_run shell input motionevent DOWN "$1" "$2"
  sleep 0.9
  adb_run shell input motionevent UP "$1" "$2"
  sleep 0.2
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

type_daigakukita() {
  tap_key 324 1942
  tap_key 324 2208
  flick_key 324 1810 245 1810
  tap_key 540 1810
  tap_key 324 2208
  flick_key 540 1810 540 1720
  flick_key 540 1810 450 1810
  tap_key 324 1942
}

require_device() {
  local state size density package_path
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
  density="$(adb_run shell wm density | tr -d '\r' | tail -n 1 | sed -nE 's/.*: ([0-9]+)$/\1/p')"
  if [[ "$density" != "$EXPECTED_DENSITY" ]]; then
    printf 'Expected Pixel test density %s, got: %s\n' "$EXPECTED_DENSITY" "$density" >&2
    exit 2
  fi
  package_path="$(adb_run shell pm path "$APP_ID" 2>/dev/null | tr -d '\r')"
  if [[ "$package_path" != package:* ]]; then
    printf 'Debug APK %s is not installed.\n' "$APP_ID" >&2
    exit 2
  fi
  INSTALLED_APP_VERSION="$(adb_run shell dumpsys package "$APP_ID" 2>/dev/null \
    | tr -d '\r' \
    | sed -nE 's/^[[:space:]]*versionName=(.*)$/\1/p' \
    | head -n 1)"
  if [[ -z "$INSTALLED_APP_VERSION" ]]; then
    printf 'Could not read installed app version for %s.\n' "$APP_ID" >&2
    exit 2
  fi
}

test_raw_delete() {
  fresh_pad
  tap_key 324 1810
  flick_key 324 1810 245 1810
  assert_text "raw input before delete" "あい"
  press_key 972 1810
  assert_text "delete one composing kana" "あ"
}

test_modifier_delete() {
  fresh_pad
  tap_key 540 1810
  tap_key 324 2208
  assert_text "dakuten modifier" "が"
  press_key 972 1810
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
  press_key 972 1810
  assert_text "delete during conversion restores shortened reading" "きょ"
}

test_committed_delete_and_undo() {
  fresh_pad "abc"
  assert_text "debug pad initial text" "abc"
  press_key 972 1810
  assert_text "delete committed character" "ab"
  tap_key 108 1810
  assert_text "undo restores deleted character" "abc"
}

test_undo_is_invalidated_after_cursor_move() {
  fresh_pad "abc"
  press_key 972 1810
  assert_text "delete before moving away from undo anchor" "ab"
  tap_key 108 1942
  tap_key 108 1810
  assert_text "undo does not restore at a different cursor" "ab"
}

test_cursor_delete() {
  fresh_pad "abc"
  tap_key 108 1942
  press_key 972 1810
  assert_text "delete respects moved cursor" "ac"
}

test_selection_and_start_delete() {
  fresh_pad "abcdef" "$CODE_PACKAGE.TEST_DELETE_ONE" 2 5
  assert_text "delete replaces selected range" "abf"
  tap_key 108 1810
  assert_text "undo restores selected range" "abcdef"

  fresh_pad "abc" "$CODE_PACKAGE.TEST_DELETE_ONE" 0 0
  assert_text "delete at document start is a no-op" "abc"
}

test_word_swipe_delete() {
  fresh_pad "abc test" "$CODE_PACKAGE.TEST_DELETE_WORD"
  assert_text "left swipe deletes previous word" "abc "
}

test_long_press_delete() {
  fresh_pad "abcdef" "$CODE_PACKAGE.TEST_REPEAT_DELETE"
  assert_text "long press repeat path deletes five characters" "a"
}

test_accelerated_long_press_delete() {
  fresh_pad "abcdefghijklmnopqrstuvwxyz1234"
  adb_run shell input swipe 972 1810 972 1810 1200
  local actual
  actual="$(editor_text)"
  if [[ ${#actual} -le 20 ]]; then
    pass_case "1200ms backspace hold accelerates"
  else
    fail_case "1200ms backspace hold accelerates" "remaining=[$actual] length=${#actual}"
  fi
}

test_cursor_flick_hold_repeat() {
  local initial_text before after
  initial_text="$(seq -w 0 29 | sed 's/^/000/' | cut -c1-5)"

  fresh_pad "$initial_text" "$TEST_SET_SELECTION" 179 179
  before="$(ime_cursor_position)"
  flick_hold_key 108 1942 108 1842
  after="$(ime_cursor_position)"
  if [[ "$before" == "179" && "$after" =~ ^[0-9]+$ \
      && "$after" -gt 0 && "$after" -le 161 ]]; then
    pass_case "up flick then hold repeats cursor movement"
  else
    fail_case "up flick then hold repeats cursor movement" \
      "before=[$before] after=[$after]"
  fi

  fresh_pad "$initial_text" "$TEST_SET_SELECTION" 30 30
  before="$(ime_cursor_position)"
  flick_hold_key 972 1942 972 2042
  after="$(ime_cursor_position)"
  if [[ "$before" == "30" && "$after" =~ ^[0-9]+$ \
      && "$after" -ge 48 && "$after" -lt 179 ]]; then
    pass_case "down flick then hold repeats cursor movement"
  else
    fail_case "down flick then hold repeats cursor movement" \
      "before=[$before] after=[$after]"
  fi
}

test_cursor_hold_stops_at_editor_boundaries() {
  local initial_text before after
  initial_text=$'11111\n22222\n33333\n44444\n55555'

  fresh_pad "$initial_text" "$TEST_SET_SELECTION" 29 29
  before="$(ime_cursor_position)"
  flick_hold_key 108 1942 108 1842
  after="$(ime_cursor_position)"
  if [[ "$before" == "29" && "$after" == "0" ]] && ime_editor_has_focus; then
    pass_case "up flick then hold stops inside the editor"
  else
    fail_case "up flick then hold stops inside the editor" \
      "before=[$before] after=[$after] editor_focus=$(ime_editor_has_focus && printf yes || printf no)"
  fi

  fresh_pad "$initial_text" "$TEST_SET_SELECTION" 0 0
  before="$(ime_cursor_position)"
  flick_hold_key 972 1942 972 2042
  after="$(ime_cursor_position)"
  if [[ "$before" == "0" && "$after" == "29" ]] && ime_editor_has_focus; then
    pass_case "down flick then hold stops inside the editor"
  else
    fail_case "down flick then hold stops inside the editor" \
      "before=[$before] after=[$after] editor_focus=$(ime_editor_has_focus && printf yes || printf no)"
  fi

  fresh_pad "$initial_text" "$TEST_SET_SELECTION" 0 0
  hold_key 108 1942
  after="$(ime_cursor_position)"
  if [[ "$after" == "0" ]] && ime_editor_has_focus; then
    pass_case "left hold stops at document start"
  else
    fail_case "left hold stops at document start" \
      "after=[$after] editor_focus=$(ime_editor_has_focus && printf yes || printf no)"
  fi

  fresh_pad "$initial_text" "$TEST_SET_SELECTION" 29 29
  hold_key 972 1942
  after="$(ime_cursor_position)"
  if [[ "$after" == "29" ]] && ime_editor_has_focus; then
    pass_case "right hold stops at document end"
  else
    fail_case "right hold stops at document end" \
      "after=[$after] editor_focus=$(ime_editor_has_focus && printf yes || printf no)"
  fi
}

test_cursor_gesture_edge_cases() {
  local wrapped_text after actual

  fresh_pad "" "$TEST_SET_SELECTION" 0 0
  hold_key 108 1942
  hold_key 972 1942
  flick_hold_key 108 1942 108 1842
  flick_hold_key 972 1942 972 2042
  after="$(ime_cursor_position)"
  if [[ "$after" == "0" ]] && ime_editor_has_focus; then
    pass_case "all held cursor directions stay in an empty editor"
  else
    fail_case "all held cursor directions stay in an empty editor" \
      "after=[$after] editor_focus=$(ime_editor_has_focus && printf yes || printf no)"
  fi

  printf -v wrapped_text '%*s' 240 ''
  wrapped_text="${wrapped_text// /x}"
  fresh_pad "$wrapped_text" "$TEST_SET_SELECTION" 240 240
  flick_hold_key 108 1942 108 1842
  after="$(ime_cursor_position)"
  if [[ "$after" == "0" ]] && ime_editor_has_focus; then
    pass_case "up flick then hold stops in wrapped text"
  else
    fail_case "up flick then hold stops in wrapped text" \
      "after=[$after] editor_focus=$(ime_editor_has_focus && printf yes || printf no)"
  fi

  fresh_pad "$wrapped_text" "$TEST_SET_SELECTION" 0 0
  flick_hold_key 972 1942 972 2042
  after="$(ime_cursor_position)"
  if [[ "$after" == "240" ]] && ime_editor_has_focus; then
    pass_case "down flick then hold stops in wrapped text"
  else
    fail_case "down flick then hold stops in wrapped text" \
      "after=[$after] editor_focus=$(ime_editor_has_focus && printf yes || printf no)"
  fi

  fresh_pad "abc" "$TEST_SET_SELECTION" 0 0
  flick_key 972 2074 790 2074
  after="$(ime_cursor_position)"
  actual="$(editor_text)"
  if [[ "$after" == "0" && "$actual" == "abc" ]] && ime_editor_has_focus; then
    pass_case "space swipe left stays at document start without inserting"
  else
    fail_case "space swipe left stays at document start without inserting" \
      "after=[$after] text=[$actual] editor_focus=$(ime_editor_has_focus && printf yes || printf no)"
  fi

  fresh_pad "abc" "$TEST_SET_SELECTION" 3 3
  flick_key 972 2074 1050 2074
  after="$(ime_cursor_position)"
  actual="$(editor_text)"
  if [[ "$after" == "3" && "$actual" == "abc" ]] && ime_editor_has_focus; then
    pass_case "space swipe right stays at document end without inserting"
  else
    fail_case "space swipe right stays at document end without inserting" \
      "after=[$after] text=[$actual] editor_focus=$(ime_editor_has_focus && printf yes || printf no)"
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
  # Typing the next reading commits the highlighted conversion first. This is
  # the path that previously left the old native preedit observable.
  type_ashita
  assert_text "different reading auto-commits the selected candidate" "今日あした"
  tap_key 972 2074
  assert_text "Mozc converts the new reading instead of the stale one" "今日明日"
}

test_partial_conversion_keeps_suffix() {
  # Prepare and commit through one debug command. A fixed screen coordinate can
  # miss the candidate toolbar while leaving the same displayed text, producing
  # a false positive for the tap followed by false composition failures.
  fresh_pad "" "$TEST_COMMIT_PARTIAL_CONVERSION"
  assert_text "partial candidate commits only its consumed reading" "大学きた"
  assert_composition "partial candidate keeps only unread suffix composing" "2:4"
  flick_key 972 1810 850 1810
  assert_text "word delete removes the remaining composition only" "大学"
}

test_enter_commits_partial_conversion_suffix_once() {
  fresh_pad "" "$TEST_PREPARE_PARTIAL_CONVERSION"
  tap_key 972 2208
  assert_text "enter commits the full partial conversion" "大学きた"
  assert_composition "enter clears the composing span" "-1:-1"
  flick_key 972 1810 850 1810
  assert_text "no duplicated composing suffix remains after enter" ""
}

test_emoji_cluster_delete() {
  fresh_pad "A👨‍👩‍👧‍👦"
  press_key 972 1810
  assert_text "delete keeps ZWJ emoji atomic" "A"

  fresh_pad "A🇯🇵"
  press_key 972 1810
  assert_text "delete keeps flag emoji atomic" "A"

  fresh_pad "A👍🏽"
  press_key 972 1810
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
  press_key 972 1810
  tap_key 324 1810
  tap_key 756 2208
  tap_key 972 2208
  # Delete committed 、, replace it with い, and commit again.
  press_key 972 1810
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
  telemetry="$(adb_run exec-out run-as "$APP_ID" cat "$TELEMETRY_EXPORT" 2>/dev/null \
    | tr -d '\r')"
  if jq -e -s --arg app_version "$INSTALLED_APP_VERSION" '
      . as $rows
      | ([.[] | select(.edit_operation == "DELETE") | .correction_id][0]) as $composingCorrection
      | ([.[] | select(.edit_operation == "DELETE_COMMITTED") | .correction_id][0]) as $committedCorrection
      | length >= 8
      and ([.[] | keys | join(",")] | unique | length == 1)
      and all(.[]; .schema_version == 3 and .app_version == $app_version
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

require_host_tools
require_device
adb_run logcat -c
adb_run shell am force-stop "$APP_ID" >/dev/null
select_test_ime
printf 'Kotonoha Pixel IME regression suite\n'
# The very first bind also maps the large Mozc data file. Warm it once so gesture assertions
# never race the initial input-view animation or native engine startup.
fresh_pad
require_ime_window_visible
sleep 1
case "${1:-smoke}" in
  smoke)
    # Only behavior that crosses an Android framework or Mozc JNI boundary.
    test_raw_delete
    test_conversion_display_and_cycle
    test_partial_conversion_keeps_suffix
    test_enter_commits_partial_conversion_suffix_once
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
  cursor_gestures)
    test_cursor_flick_hold_repeat
    test_cursor_hold_stops_at_editor_boundaries
    test_cursor_gesture_edge_cases
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
  partial_conversion)
    test_partial_conversion_keeps_suffix
    test_enter_commits_partial_conversion_suffix_once
    ;;
  telemetry)
    test_telemetry_schema_v3
    ;;
  all)
    test_raw_delete
    test_modifier_delete
    test_conversion_display_and_cycle
    test_conversion_auto_commit
    test_partial_conversion_keeps_suffix
    test_enter_commits_partial_conversion_suffix_once
    test_mozc_candidates_after_different_commit
    test_delete_during_conversion
    test_committed_delete_and_undo
    test_undo_is_invalidated_after_cursor_move
    test_cursor_delete
    test_selection_and_start_delete
    test_word_swipe_delete
    test_long_press_delete
    test_accelerated_long_press_delete
    test_cursor_flick_hold_repeat
    test_cursor_hold_stops_at_editor_boundaries
    test_cursor_gesture_edge_cases
    test_emoji_cluster_delete
    test_punctuation_commit
    test_japanese_space
    test_rapid_taps
    test_telemetry_schema_v3
    ;;
  *)
    printf 'Unknown group: %s (use smoke, all, telemetry, stale_reading, partial_conversion, v012, delete_gestures, cursor_gestures, or selection_delete)\n' "$1" >&2
    exit 2
esac

printf '\nResult: %d passed, %d failed\n' "$PASS_COUNT" "$FAIL_COUNT"
if [[ "$FAIL_COUNT" -ne 0 ]]; then
  exit 1
fi
