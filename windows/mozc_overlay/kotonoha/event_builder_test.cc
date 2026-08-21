#include "kotonoha/event_builder.h"

#include <string>

#include "gtest/gtest.h"
#include "protocol/candidate_window.pb.h"
#include "protocol/commands.pb.h"

namespace mozc::kotonoha {
namespace {

commands::Command CompositionCommand() {
  commands::Command command;
  command.mutable_input()->set_type(commands::Input::SEND_KEY);
  command.mutable_input()->set_id(42);
  command.mutable_input()->mutable_key()->set_key_code('k');
  command.mutable_input()->mutable_context()->set_preceding_text("前文");
  command.mutable_output()->set_consumed(true);
  command.mutable_output()->mutable_status()->set_mode(commands::HIRAGANA);
  commands::Preedit* preedit = command.mutable_output()->mutable_preedit();
  preedit->set_cursor(1);
  auto* segment = preedit->add_segment();
  segment->set_annotation(commands::Preedit::Segment::UNDERLINE);
  segment->set_value("き");
  segment->set_value_length(1);
  segment->set_key("き");
  return command;
}

TEST(EventBuilderTest, BuildsSchemaV3CompositionEvent) {
  EventState state;
  const std::string json = BuildEventJson(CompositionCommand(), "42", "app",
                                          "Mozc test", 1234, &state);

  EXPECT_NE(json.find("\"schema_version\":3"), std::string::npos);
  EXPECT_NE(json.find("\"platform\":\"windows\""), std::string::npos);
  EXPECT_NE(json.find("\"type\":\"COMPOSITION_EDIT\""), std::string::npos);
  EXPECT_NE(json.find("\"reading\":\"き\""), std::string::npos);
  EXPECT_NE(json.find("\"context_before\":\"前文\""), std::string::npos);
  EXPECT_NE(json.find("\"cursor_before\":-1,\"cursor_after\":1"),
            std::string::npos);
  EXPECT_EQ(state.sequence, 1);
  EXPECT_EQ(state.composition_id, "42-composition-1");
  EXPECT_EQ(state.cursor, 1);
}

TEST(EventBuilderTest, KeepsCandidatesForCandidateCommit) {
  EventState state;
  commands::Command candidates = CompositionCommand();
  commands::CandidateList* list =
      candidates.mutable_output()->mutable_all_candidate_words();
  list->set_category(commands::PREDICTION);
  list->set_focused_index(0);
  auto* first = list->add_candidates();
  first->set_index(0);
  first->set_value("今日");
  auto* second = list->add_candidates();
  second->set_index(1);
  second->set_value("きょう");
  BuildEventJson(candidates, "42", "app", "Mozc test", 1234, &state);

  commands::Command commit;
  commit.mutable_input()->set_type(commands::Input::SEND_COMMAND);
  commit.mutable_input()->set_id(42);
  commit.mutable_input()->mutable_command()->set_type(
      commands::SessionCommand::SELECT_CANDIDATE);
  commands::Result* result = commit.mutable_output()->mutable_result();
  result->set_type(commands::Result::STRING);
  result->set_value("今日");
  result->set_key("きょう");

  const std::string json =
      BuildEventJson(commit, "42", "app", "Mozc test", 1235, &state);
  EXPECT_NE(json.find("\"type\":\"CONVERSION_COMMIT\""), std::string::npos);
  EXPECT_NE(json.find("\"candidates\":[\"今日\",\"きょう\"]"),
            std::string::npos);
  EXPECT_NE(json.find("\"selected_index\":0"), std::string::npos);
  EXPECT_NE(json.find("\"commit_method\":\"CANDIDATE_SELECT\""),
            std::string::npos);
  EXPECT_TRUE(state.reading.empty());
  EXPECT_EQ(state.cursor, -1);
}

TEST(EventBuilderTest, CapturesCommittedBackspaceFromContext) {
  EventState state;
  commands::Command command;
  command.mutable_input()->set_type(commands::Input::SEND_KEY);
  command.mutable_input()->set_id(42);
  command.mutable_input()->mutable_key()->set_special_key(
      commands::KeyEvent::BACKSPACE);
  command.mutable_input()->mutable_context()->set_preceding_text("abc猫");
  command.mutable_output()->set_consumed(false);

  const std::string json =
      BuildEventJson(command, "42", "app", "Mozc test", 1234, &state);
  EXPECT_NE(json.find("\"type\":\"DELETE_COMMITTED\""), std::string::npos);
  EXPECT_NE(json.find("\"deleted_text\":\"猫\""), std::string::npos);
  EXPECT_EQ(state.correction_id, "42-correction-1");
}

TEST(EventBuilderTest, RejectsPasswordFields) {
  EventState state;
  state.reading = "secret";
  state.preedit = "secret";
  state.candidates = {"secret candidate"};
  state.selected_index = 0;
  state.candidate_source = "PREDICTION";
  state.composition_id = "composition-secret";
  state.correction_id = "correction-secret";
  state.cursor = 6;
  commands::Command command = CompositionCommand();
  command.mutable_input()->mutable_context()->set_input_field_type(
      commands::Context::PASSWORD);
  EXPECT_TRUE(
      BuildEventJson(command, "42", "app", "Mozc test", 1234, &state).empty());
  EXPECT_TRUE(state.reading.empty());
  EXPECT_TRUE(state.candidates.empty());
  EXPECT_TRUE(state.composition_id.empty());
  EXPECT_TRUE(state.correction_id.empty());
  EXPECT_EQ(state.cursor, -1);
}

TEST(EventBuilderTest, ClearsCandidateStateWhenCompositionIsCancelled) {
  EventState state;
  commands::Command candidates = CompositionCommand();
  commands::CandidateList* list =
      candidates.mutable_output()->mutable_all_candidate_words();
  list->set_category(commands::SUGGESTION);
  list->add_candidates()->set_value("木");
  BuildEventJson(candidates, "42", "app", "Mozc test", 1234, &state);
  ASSERT_FALSE(state.candidates.empty());

  commands::Command cancel;
  cancel.mutable_input()->set_type(commands::Input::SEND_KEY);
  cancel.mutable_input()->set_id(42);
  cancel.mutable_input()->mutable_key()->set_special_key(
      commands::KeyEvent::ESCAPE);
  cancel.mutable_output()->set_consumed(true);
  BuildEventJson(cancel, "42", "app", "Mozc test", 1235, &state);

  EXPECT_TRUE(state.reading.empty());
  EXPECT_TRUE(state.candidates.empty());
  EXPECT_TRUE(state.composition_id.empty());
  EXPECT_EQ(state.cursor, -1);
}

TEST(EventBuilderTest, EscapesJsonControlCharacters) {
  EXPECT_EQ(JsonEscapeForTest("a\n\"b\\c"), "\"a\\n\\\"b\\\\c\"");
}

}  // namespace
}  // namespace mozc::kotonoha
