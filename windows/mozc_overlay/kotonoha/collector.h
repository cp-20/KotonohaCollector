#ifndef MOZC_KOTONOHA_COLLECTOR_H_
#define MOZC_KOTONOHA_COLLECTOR_H_

#include <memory>

#include "protocol/commands.pb.h"

namespace mozc::kotonoha {

// Observes evaluated Mozc commands and writes encrypted events off the input
// thread. One Collector belongs to the single per-user Mozc server process.
class Collector final {
 public:
  Collector();
  Collector(const Collector&) = delete;
  Collector& operator=(const Collector&) = delete;
  ~Collector();

  void Observe(const commands::Command& command);

 private:
  class Impl;
  std::unique_ptr<Impl> impl_;
};

}  // namespace mozc::kotonoha

#endif  // MOZC_KOTONOHA_COLLECTOR_H_
