#include "kotonoha/event_builder.h"

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <string>
#include <utility>
#include <vector>

#include "absl/strings/str_cat.h"
#include "absl/strings/string_view.h"

namespace mozc::kotonoha {
namespace {

constexpr size_t kMaxCandidates = 80;

std::string JsonEscape(absl::string_view value) {
  std::string result;
  result.reserve(value.size() + 2);
  result.push_back('"');
  constexpr char kHex[] = "0123456789abcdef";
  for (const unsigned char ch : value) {
    switch (ch) {
      case '"':
        result.append("\\\"");
        break;
      case '\\':
        result.append("\\\\");
        break;
      case '\b':
        result.append("\\b");
        break;
      case '\f':
        result.append("\\f");
        break;
      case '\n':
        result.append("\\n");
        break;
      case '\r':
        result.append("\\r");
        break;
      case '\t':
        result.append("\\t");
        break;
      default:
        if (ch < 0x20) {
          result.append("\\u00");
          result.push_back(kHex[(ch >> 4) & 0x0f]);
          result.push_back(kHex[ch & 0x0f]);
        } else {
          result.push_back(static_cast<char>(ch));
        }
    }
  }
  result.push_back('"');
  return result;
}

std::string JsonArray(const std::vector<std::string>& values) {
  std::string result = "[";
  for (size_t i = 0; i < values.size(); ++i) {
    if (i != 0) {
      result.push_back(',');
    }
    result.append(JsonEscape(values[i]));
  }
  result.push_back(']');
  return result;
}

std::string JoinPreeditValue(const commands::Preedit& preedit) {
  std::string result;
  for (const auto& segment : preedit.segment()) {
    result.append(segment.value());
  }
  return result;
}

std::string JoinPreeditReading(const commands::Preedit& preedit) {
  std::string result;
  for (const auto& segment : preedit.segment()) {
    result.append(segment.has_key() ? segment.key() : segment.value());
  }
  return result;
}

std::string InputModeName(commands::CompositionMode mode) {
  switch (mode) {
    case commands::HIRAGANA:
      return "HIRAGANA";
    case commands::FULL_KATAKANA:
      return "FULL_KATAKANA";
    case commands::HALF_KATAKANA:
      return "HALF_KATAKANA";
    case commands::HALF_ASCII:
      return "HALF_ASCII";
    case commands::FULL_ASCII:
      return "FULL_ASCII";
    case commands::DIRECT:
    default:
      return "DIRECT";
  }
}

std::string CandidateSourceName(commands::Category category) {
  switch (category) {
    case commands::PREDICTION:
      return "PREDICTION";
    case commands::SUGGESTION:
      return "SUGGESTION";
    case commands::TRANSLITERATION:
      return "TRANSLITERATION";
    case commands::USAGE:
      return "USAGE";
    case commands::CONVERSION:
    default:
      return "CONVERSION";
  }
}

struct CandidateSnapshot {
  bool present = false;
  std::vector<std::string> values;
  int focused_index = -1;
  std::string source = "NONE";
};

CandidateSnapshot ExtractCandidates(const commands::Output& output) {
  CandidateSnapshot snapshot;
  if (output.has_all_candidate_words()) {
    const commands::CandidateList& list = output.all_candidate_words();
    snapshot.present = true;
    snapshot.focused_index =
        list.has_focused_index() ? static_cast<int>(list.focused_index()) : -1;
    snapshot.source = CandidateSourceName(list.category());
    for (const auto& candidate : list.candidates()) {
      if (snapshot.values.size() >= kMaxCandidates) {
        break;
      }
      snapshot.values.push_back(candidate.value());
    }
    return snapshot;
  }
  if (output.has_candidate_window()) {
    const commands::CandidateWindow& window = output.candidate_window();
    snapshot.present = true;
    snapshot.focused_index = window.has_focused_index()
                                 ? static_cast<int>(window.focused_index())
                                 : -1;
    snapshot.source = CandidateSourceName(window.category());
    for (const auto& candidate : window.candidate()) {
      if (snapshot.values.size() >= kMaxCandidates) {
        break;
      }
      snapshot.values.push_back(candidate.value());
    }
  }
  return snapshot;
}

std::string RawKey(const commands::Input& input) {
  if (!input.has_key()) {
    return "";
  }
  const commands::KeyEvent& key = input.key();
  if (key.has_key_string()) {
    return key.key_string();
  }
  if (key.has_key_code()) {
    const uint32_t code = key.key_code();
    if (code >= 0x20 && code <= 0x7e) {
      return std::string(1, static_cast<char>(code));
    }
    return absl::StrCat("code:", code);
  }
  if (key.has_special_key()) {
    return absl::StrCat("special:", static_cast<int>(key.special_key()));
  }
  return "";
}

std::string LastUtf8Character(absl::string_view value) {
  if (value.empty()) {
    return "";
  }
  size_t start = value.size() - 1;
  while (start > 0 &&
         (static_cast<unsigned char>(value[start]) & 0xc0) == 0x80) {
    --start;
  }
  return std::string(value.substr(start));
}

std::string FirstUtf8Character(absl::string_view value) {
  if (value.empty()) {
    return "";
  }
  size_t length = 1;
  const unsigned char lead = static_cast<unsigned char>(value.front());
  if ((lead & 0xe0) == 0xc0) {
    length = 2;
  } else if ((lead & 0xf0) == 0xe0) {
    length = 3;
  } else if ((lead & 0xf8) == 0xf0) {
    length = 4;
  }
  return std::string(value.substr(0, std::min(length, value.size())));
}

std::string DeletedCompositionText(absl::string_view before,
                                   absl::string_view after) {
  if (before.size() > after.size() && before.substr(0, after.size()) == after) {
    return std::string(before.substr(after.size()));
  }
  return before.size() > after.size() ? std::string(before) : "";
}

std::string CommitMethod(const commands::Input& input) {
  if (input.type() == commands::Input::SEND_COMMAND && input.has_command()) {
    switch (input.command().type()) {
      case commands::SessionCommand::SELECT_CANDIDATE:
      case commands::SessionCommand::SUBMIT_CANDIDATE:
        return "CANDIDATE_SELECT";
      case commands::SessionCommand::SUBMIT:
        return "ENTER";
      case commands::SessionCommand::COMMIT_RAW_TEXT:
        return "RAW_TEXT";
      default:
        return "SESSION_COMMAND";
    }
  }
  if (input.has_key() && input.key().has_special_key()) {
    switch (input.key().special_key()) {
      case commands::KeyEvent::ENTER:
      case commands::KeyEvent::VIRTUAL_ENTER:
        return "ENTER";
      case commands::KeyEvent::SPACE:
      case commands::KeyEvent::HENKAN:
        return "CONVERSION_KEY";
      default:
        break;
    }
  }
  return "KEY_EVENT";
}

int FindSelectedIndex(const std::vector<std::string>& candidates,
                      absl::string_view committed_text, int fallback) {
  const auto found =
      std::find(candidates.begin(), candidates.end(), committed_text);
  if (found == candidates.end()) {
    return fallback;
  }
  return static_cast<int>(std::distance(candidates.begin(), found));
}

}  // namespace

std::string JsonEscapeForTest(absl::string_view value) {
  return JsonEscape(value);
}

std::string BuildEventJson(const commands::Command& command,
                           absl::string_view session_id,
                           absl::string_view app_id,
                           absl::string_view engine_version,
                           int64_t timestamp_ms, EventState* state) {
  if (state == nullptr || !command.has_input()) {
    return "";
  }
  const commands::Input& input = command.input();
  if (input.type() != commands::Input::SEND_KEY &&
      input.type() != commands::Input::SEND_COMMAND) {
    return "";
  }
  if (input.has_context() && input.context().has_input_field_type() &&
      input.context().input_field_type() == commands::Context::PASSWORD) {
    state->reading.clear();
    state->preedit.clear();
    state->candidates.clear();
    state->selected_index = -1;
    state->candidate_source = "NONE";
    state->composition_id.clear();
    state->correction_id.clear();
    state->cursor = -1;
    return "";
  }

  const commands::Output& output = command.output();
  if (output.has_status() && output.status().has_mode()) {
    state->input_mode = InputModeName(output.status().mode());
  } else if (output.has_mode()) {
    state->input_mode = InputModeName(output.mode());
  }

  const std::string previous_reading = state->reading;
  const std::string previous_preedit = state->preedit;
  const int cursor_before = state->cursor;
  std::string current_preedit;
  std::string current_reading;
  int cursor_after = -1;
  if (output.has_preedit()) {
    current_preedit = JoinPreeditValue(output.preedit());
    current_reading = JoinPreeditReading(output.preedit());
    cursor_after = static_cast<int>(output.preedit().cursor());
  }

  const CandidateSnapshot snapshot = ExtractCandidates(output);
  const std::vector<std::string>& effective_candidates =
      snapshot.present ? snapshot.values : state->candidates;
  const std::string effective_source =
      snapshot.present ? snapshot.source : state->candidate_source;
  const int effective_focused =
      snapshot.present ? snapshot.focused_index : state->selected_index;

  const bool has_result =
      output.has_result() && !output.result().value().empty();
  const bool backspace =
      input.has_key() && input.key().has_special_key() &&
      input.key().special_key() == commands::KeyEvent::BACKSPACE;
  const bool forward_delete =
      input.has_key() && input.key().has_special_key() &&
      input.key().special_key() == commands::KeyEvent::DEL;

  std::string event_type = "KEY_EVENT";
  std::string edit_operation;
  std::string deleted_text;
  std::string committed_text;
  std::string reading = current_reading;
  int selected_index = effective_focused;
  if (has_result) {
    committed_text = output.result().value();
    reading =
        output.result().has_key() ? output.result().key() : previous_reading;
    selected_index = FindSelectedIndex(effective_candidates, committed_text,
                                       effective_focused);
    event_type =
        effective_candidates.empty() ? "READING_COMMIT" : "CONVERSION_COMMIT";
  } else if (output.has_deletion_range()) {
    event_type = "DELETE_COMMITTED";
    if (input.has_context()) {
      deleted_text = LastUtf8Character(input.context().preceding_text());
    }
  } else if (previous_reading != current_reading ||
             previous_preedit != current_preedit) {
    event_type = "COMPOSITION_EDIT";
    if (previous_reading.empty() && !current_reading.empty()) {
      edit_operation = "INSERT";
    } else if (previous_reading.size() > current_reading.size()) {
      edit_operation = "DELETE";
      deleted_text = DeletedCompositionText(previous_reading, current_reading);
    } else {
      edit_operation = "REPLACE";
    }
  } else if ((backspace || forward_delete) && input.has_context()) {
    event_type = "DELETE_COMMITTED";
    deleted_text = backspace
                       ? LastUtf8Character(input.context().preceding_text())
                       : FirstUtf8Character(input.context().following_text());
  } else if (snapshot.present) {
    event_type = "CANDIDATES_SHOWN";
  }

  if (previous_reading.empty() && !current_reading.empty()) {
    ++state->composition_counter;
    state->composition_id =
        absl::StrCat(session_id, "-composition-", state->composition_counter);
    state->correction_id.clear();
  }
  if (event_type == "DELETE_COMMITTED" || edit_operation == "DELETE") {
    ++state->correction_counter;
    state->correction_id =
        absl::StrCat(session_id, "-correction-", state->correction_counter);
  }

  ++state->sequence;
  const int input_type =
      input.has_context() && input.context().has_input_field_type()
          ? static_cast<int>(input.context().input_field_type())
          : 0;
  const std::string context_before =
      input.has_context() ? input.context().preceding_text() : "";
  const std::string context_after =
      input.has_context() ? input.context().following_text() : "";
  const int session_command =
      input.has_command() ? static_cast<int>(input.command().type()) : 0;
  const bool consumed = output.has_consumed() && output.consumed();

  std::string json;
  absl::StrAppend(
      &json, "{\"schema_version\":3,\"platform\":\"windows\"",
      ",\"timestamp_ms\":", timestamp_ms,
      ",\"session_id\":", JsonEscape(session_id),
      ",\"sequence\":", state->sequence, ",\"type\":", JsonEscape(event_type),
      ",\"app_id\":", JsonEscape(app_id), ",\"input_type\":", input_type,
      ",\"input_mode\":", JsonEscape(state->input_mode),
      ",\"raw_input\":", JsonEscape(RawKey(input)),
      ",\"reading\":", JsonEscape(reading),
      ",\"committed_text\":", JsonEscape(committed_text),
      ",\"candidates\":", JsonArray(effective_candidates),
      ",\"selected_index\":", selected_index,
      ",\"context_before\":", JsonEscape(context_before),
      ",\"context_after\":", JsonEscape(context_after),
      ",\"composition_id\":", JsonEscape(state->composition_id),
      ",\"correction_id\":", JsonEscape(state->correction_id),
      ",\"candidate_source\":", JsonEscape(effective_source),
      ",\"commit_method\":", JsonEscape(CommitMethod(input)),
      ",\"edit_operation\":", JsonEscape(edit_operation),
      ",\"raw_before\":", JsonEscape(previous_reading),
      ",\"raw_after\":", JsonEscape(current_reading),
      ",\"deleted_text\":", JsonEscape(deleted_text),
      ",\"cursor_before\":", cursor_before, ",\"cursor_after\":", cursor_after,
      ",\"engine_version\":", JsonEscape(engine_version),
      ",\"app_version\":\"windows-mvp-0.1.0\"",
      ",\"layout_version\":\"windows-tsf-mozc-v1\"",
      ",\"gesture_key\":\"\",\"gesture_direction\":\"\"",
      ",\"gesture_dx_dp\":0,\"gesture_dy_dp\":0", ",\"gesture_duration_ms\":0",
      ",\"gesture_start_x_ratio\":-1,\"gesture_start_y_ratio\":-1",
      ",\"gesture_long_press\":false",
      ",\"mozc_input_command\":", static_cast<int>(input.type()),
      ",\"mozc_session_command\":", session_command,
      ",\"consumed\":", consumed ? "true" : "false", "}");

  if (has_result) {
    state->reading.clear();
    state->preedit.clear();
    state->candidates.clear();
    state->selected_index = -1;
    state->candidate_source = "NONE";
    state->composition_id.clear();
    state->cursor = -1;
  } else {
    state->reading = std::move(current_reading);
    state->preedit = std::move(current_preedit);
    state->cursor = cursor_after;
    if (snapshot.present) {
      state->candidates = snapshot.values;
      state->selected_index = snapshot.focused_index;
      state->candidate_source = snapshot.source;
    }
    if (state->reading.empty() && state->preedit.empty()) {
      state->candidates.clear();
      state->selected_index = -1;
      state->candidate_source = "NONE";
      state->composition_id.clear();
    }
  }
  return json;
}

}  // namespace mozc::kotonoha
