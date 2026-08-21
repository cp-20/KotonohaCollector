#ifndef MOZC_KOTONOHA_EVENT_BUILDER_H_
#define MOZC_KOTONOHA_EVENT_BUILDER_H_

#include <cstdint>
#include <string>
#include <vector>

#include "absl/strings/string_view.h"
#include "protocol/commands.pb.h"

namespace mozc::kotonoha {

struct EventState {
  uint64_t sequence = 0;
  uint64_t composition_counter = 0;
  uint64_t correction_counter = 0;
  std::string reading;
  std::string preedit;
  std::vector<std::string> candidates;
  int selected_index = -1;
  std::string candidate_source = "NONE";
  std::string composition_id;
  std::string correction_id;
  std::string input_mode = "DIRECT";
  int cursor = -1;
};

// Converts one evaluated Mozc command into the shared Kotonoha schema v3.
// Returns an empty string for sensitive input fields and non-input commands.
std::string BuildEventJson(const commands::Command& command,
                           absl::string_view session_id,
                           absl::string_view app_id,
                           absl::string_view engine_version,
                           int64_t timestamp_ms, EventState* state);

std::string JsonEscapeForTest(absl::string_view value);

}  // namespace mozc::kotonoha

#endif  // MOZC_KOTONOHA_EVENT_BUILDER_H_
