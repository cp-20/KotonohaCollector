#include "kotonoha/collector.h"

#include <windows.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <unordered_map>
#include <utility>

#include "absl/strings/str_cat.h"
#include "absl/time/clock.h"
#include "absl/time/time.h"
#include "base/encryptor.h"
#include "base/file_util.h"
#include "base/system_util.h"
#include "base/version.h"
#include "kotonoha/event_builder.h"

namespace mozc::kotonoha {
namespace {

constexpr size_t kMaxQueuedEvents = 2048;
constexpr uint32_t kMaxEncryptedEventBytes = 16 * 1024 * 1024;
constexpr char kEnabledFile[] = "kotonoha-collector.enabled";
constexpr char kEventFile[] = "kotonoha-events.bin";

std::string HashApplicationName(uint32_t process_id) {
  if (process_id == 0) {
    return "unknown";
  }
  HANDLE process =
      ::OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, process_id);
  if (process == nullptr) {
    return "unknown";
  }
  std::wstring path(32768, L'\0');
  DWORD length = static_cast<DWORD>(path.size());
  const BOOL succeeded =
      ::QueryFullProcessImageNameW(process, 0, path.data(), &length);
  ::CloseHandle(process);
  if (!succeeded || length == 0) {
    return "unknown";
  }
  path.resize(length);
  const size_t separator = path.find_last_of(L"\\/");
  const std::wstring name =
      separator == std::wstring::npos ? path : path.substr(separator + 1);

  // Stable FNV-1a over the lower 16 bits of the executable basename.
  uint64_t hash = 1469598103934665603ULL;
  for (wchar_t ch : name) {
    if (ch >= L'A' && ch <= L'Z') {
      ch = static_cast<wchar_t>(ch - L'A' + L'a');
    }
    hash ^= static_cast<uint16_t>(ch);
    hash *= 1099511628211ULL;
  }
  std::ostringstream stream;
  stream << std::hex << std::setw(16) << std::setfill('0') << hash;
  return stream.str();
}

std::filesystem::path Utf8Path(const std::string& value) {
  return std::filesystem::u8path(value);
}

}  // namespace

class Collector::Impl final {
 public:
  Impl()
      : profile_directory_(SystemUtil::GetUserProfileDirectory()),
        enabled_path_(FileUtil::JoinPath(profile_directory_, kEnabledFile)),
        events_path_(FileUtil::JoinPath(profile_directory_, kEventFile)),
        worker_(&Impl::WorkerMain, this) {}

  Impl(const Impl&) = delete;
  Impl& operator=(const Impl&) = delete;

  ~Impl() {
    {
      std::lock_guard<std::mutex> lock(queue_mutex_);
      stop_ = true;
    }
    queue_changed_.notify_one();
    if (worker_.joinable()) {
      worker_.join();
    }
  }

  void Observe(const commands::Command& command) {
    if (!command.has_input()) {
      return;
    }
    const commands::Input& input = command.input();
    if (input.type() == commands::Input::CREATE_SESSION) {
      if (!command.has_output() || !command.output().has_id()) {
        return;
      }
      SessionData data;
      if (input.has_application_info() &&
          input.application_info().has_process_id()) {
        data.app_id =
            HashApplicationName(input.application_info().process_id());
      }
      sessions_[command.output().id()] = std::move(data);
      return;
    }
    if (!input.has_id()) {
      return;
    }
    const uint64_t session_id = input.id();
    if (input.type() == commands::Input::DELETE_SESSION) {
      sessions_.erase(session_id);
      return;
    }
    if (!enabled_.load(std::memory_order_relaxed)) {
      return;
    }

    SessionData& data = sessions_[session_id];
    if (input.has_application_info() &&
        input.application_info().has_process_id()) {
      data.app_id = HashApplicationName(input.application_info().process_id());
    }
    if (data.app_id.empty()) {
      data.app_id = "unknown";
    }
    const int64_t timestamp_ms = absl::ToUnixMillis(absl::Now());
    const std::string json = BuildEventJson(
        command, absl::StrCat(session_id), data.app_id,
        Version::GetMozcVersion(), timestamp_ms, &data.event_state);
    if (!json.empty()) {
      Enqueue(json);
    }
  }

 private:
  struct SessionData {
    std::string app_id = "unknown";
    EventState event_state;
  };

  void Enqueue(const std::string& json) {
    {
      std::lock_guard<std::mutex> lock(queue_mutex_);
      if (queue_.size() >= kMaxQueuedEvents) {
        queue_.pop_front();
      }
      queue_.push_back(json);
    }
    queue_changed_.notify_one();
  }

  void RefreshEnabled() {
    enabled_.store(FileUtil::FileExists(enabled_path_).ok(),
                   std::memory_order_relaxed);
  }

  bool WriteEncrypted(const std::string& json) {
    std::string encrypted;
    if (!Encryptor::ProtectData(json, &encrypted) || encrypted.empty() ||
        encrypted.size() > kMaxEncryptedEventBytes) {
      return false;
    }
    const uint32_t size = static_cast<uint32_t>(encrypted.size());
    const char header[4] = {
        static_cast<char>(size & 0xff),
        static_cast<char>((size >> 8) & 0xff),
        static_cast<char>((size >> 16) & 0xff),
        static_cast<char>((size >> 24) & 0xff),
    };
    std::ofstream output(Utf8Path(events_path_),
                         std::ios::binary | std::ios::app);
    if (!output) {
      return false;
    }
    output.write(header, sizeof(header));
    output.write(encrypted.data(), encrypted.size());
    output.flush();
    return output.good();
  }

  void WorkerMain() {
    auto next_enabled_refresh = std::chrono::steady_clock::now();
    for (;;) {
      std::string event;
      {
        std::unique_lock<std::mutex> lock(queue_mutex_);
        queue_changed_.wait_for(lock, std::chrono::milliseconds(250),
                                [this] { return stop_ || !queue_.empty(); });
        if (stop_ && queue_.empty()) {
          break;
        }
        if (!queue_.empty()) {
          event = std::move(queue_.front());
          queue_.pop_front();
        }
      }

      const auto now = std::chrono::steady_clock::now();
      if (now >= next_enabled_refresh) {
        RefreshEnabled();
        next_enabled_refresh = now + std::chrono::seconds(1);
      }
      if (!enabled_.load(std::memory_order_relaxed)) {
        std::lock_guard<std::mutex> lock(queue_mutex_);
        queue_.clear();
        continue;
      }
      if (!event.empty()) {
        WriteEncrypted(event);
      }
    }
  }

  const std::string profile_directory_;
  const std::string enabled_path_;
  const std::string events_path_;
  std::atomic<bool> enabled_{false};
  std::unordered_map<uint64_t, SessionData> sessions_;

  std::mutex queue_mutex_;
  std::condition_variable queue_changed_;
  std::deque<std::string> queue_;
  bool stop_ = false;
  std::thread worker_;
};

Collector::Collector() : impl_(std::make_unique<Impl>()) {}
Collector::~Collector() = default;

void Collector::Observe(const commands::Command& command) {
  impl_->Observe(command);
}

}  // namespace mozc::kotonoha
