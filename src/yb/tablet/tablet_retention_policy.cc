// Copyright (c) YugaByte, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed under the License
// is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
// or implied.  See the License for the specific language governing permissions and limitations
// under the License.
//

#include "yb/tablet/tablet_retention_policy.h"

#include <iosfwd>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "yb/common/common_fwd.h"
#include "yb/common/schema.h"
#include "yb/common/snapshot.h"
#include "yb/common/transaction_error.h"

#include "yb/dockv/doc_ttl_util.h"

#include "yb/gutil/ref_counted.h"

#include "yb/rocksdb/options.h"
#include "yb/rocksdb/types.h"

#include "yb/server/hybrid_clock.h"

#include "yb/tablet/tablet_fwd.h"
#include "yb/tablet/tablet_metadata.h"

#include "yb/util/enums.h"
#include "yb/util/logging.h"
#include "yb/util/strongly_typed_bool.h"
#include "yb/util/flags.h"

using namespace std::literals;

DEFINE_UNKNOWN_int32(timestamp_history_retention_interval_sec, 900,
             "The time interval in seconds to retain DocDB history for. Point-in-time reads at a "
             "hybrid time further than this in the past might not be allowed after a compaction. "
             "Set this to be higher than the expected maximum duration of any single transaction "
             "in your application.");

DEFINE_UNKNOWN_int32(timestamp_syscatalog_history_retention_interval_sec, 4 * 3600,
    "The time interval in seconds to retain syscatalog history for CDC to read specific schema "
    "version. Point-in-time reads at a hybrid time further than this in the past might not be "
    "allowed after a compaction. Set this to be higher than the expected maximum duration of any "
    "single transaction in your application.");

DEFINE_UNKNOWN_bool(enable_history_cutoff_propagation, false,
            "Should we use history cutoff propagation (true) or calculate it locally (false).");

DEFINE_UNKNOWN_int32(history_cutoff_propagation_interval_ms, 180000,
             "History cutoff propagation interval in milliseconds.");

DEFINE_test_flag(uint64, committed_history_cutoff_initial_value_usec, 0,
                 "Initial value for committed_history_cutoff_");

namespace yb {
namespace tablet {

using docdb::HistoryRetentionDirective;

namespace {

HybridTime ClockBasedHistoryCutoff(server::Clock* clock) {
  return clock->Now().AddSeconds(
      -ANNOTATE_UNPROTECTED_READ(FLAGS_timestamp_history_retention_interval_sec));
}

}

TabletRetentionPolicy::TabletRetentionPolicy(
    server::ClockPtr clock, const AllowedHistoryCutoffProvider& allowed_history_cutoff_provider,
    RaftGroupMetadata* metadata)
    : clock_(std::move(clock)), allowed_history_cutoff_provider_(allowed_history_cutoff_provider),
      metadata_(*metadata), log_prefix_(metadata->LogPrefix()) {
    if (PREDICT_FALSE(FLAGS_TEST_committed_history_cutoff_initial_value_usec > 0)) {
      committed_history_cutoff_ = HybridTime::FromMicros(
          FLAGS_TEST_committed_history_cutoff_initial_value_usec);
      LOG(INFO) << "Initial value of committed_history_cutoff_ is " << committed_history_cutoff_;
    }
}

HybridTime TabletRetentionPolicy::UpdateCommittedHistoryCutoff(HybridTime value) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (!value) {
    return committed_history_cutoff_;
  }

  VLOG_WITH_PREFIX(4) << __func__ << "(" << value << ")";

  committed_history_cutoff_ = std::max(committed_history_cutoff_, value);
  return committed_history_cutoff_;
}

HistoryRetentionDirective TabletRetentionPolicy::GetRetentionDirective() {
  HybridTime history_cutoff;
  {
    std::lock_guard<std::mutex> lock(mutex_);
    if (FLAGS_enable_history_cutoff_propagation) {
      history_cutoff = SanitizeHistoryCutoff(committed_history_cutoff_);
    } else {
      history_cutoff = EffectiveHistoryCutoff();
      committed_history_cutoff_ = std::max(history_cutoff, committed_history_cutoff_);
    }
  }

  return {history_cutoff, dockv::TableTTL(*metadata_.schema()),
          docdb::ShouldRetainDeleteMarkersInMajorCompaction(
              ShouldRetainDeleteMarkersInMajorCompaction())};
}

HybridTime TabletRetentionPolicy::ProposedHistoryCutoff() {
  // For proposed history cutoff we don't respect active readers.
  std::lock_guard<std::mutex> lock(mutex_);
  return FLAGS_enable_history_cutoff_propagation
      ? committed_history_cutoff_ : ClockBasedHistoryCutoff(clock_.get());
}

Status TabletRetentionPolicy::RegisterReaderTimestamp(HybridTime timestamp) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (timestamp < committed_history_cutoff_) {
    return STATUS(
        SnapshotTooOld,
        Format(
            "Snapshot too old. Read point: $0, earliest read time allowed: $1, delta (usec): $2",
            timestamp,
            committed_history_cutoff_,
            committed_history_cutoff_.PhysicalDiff(timestamp)),
        TransactionError(TransactionErrorCode::kSnapshotTooOld));
  }
  active_readers_.insert(timestamp);
  return Status::OK();
}

void TabletRetentionPolicy::UnregisterReaderTimestamp(HybridTime timestamp) {
  std::lock_guard<std::mutex> lock(mutex_);
  active_readers_.erase(timestamp);
}

bool TabletRetentionPolicy::ShouldRetainDeleteMarkersInMajorCompaction() const {
  // If the index table is in the process of being backfilled, then we
  // want to retain delete markers until the backfill process is complete.
  return metadata_.schema()->table_properties().retain_delete_markers();
}

HybridTime TabletRetentionPolicy::HistoryCutoffToPropagate(HybridTime last_write_ht) {
  std::lock_guard<std::mutex> lock(mutex_);

  auto now = CoarseMonoClock::now();

  VLOG_WITH_PREFIX(4) << __func__ << "(" << last_write_ht << "), left to wait: "
                      << MonoDelta(next_history_cutoff_propagation_ - now);

  if (disable_counter_ != 0 || !FLAGS_enable_history_cutoff_propagation ||
      now < next_history_cutoff_propagation_ || last_write_ht <= committed_history_cutoff_) {
    return HybridTime();
  }

  next_history_cutoff_propagation_ =
      now + ANNOTATE_UNPROTECTED_READ(FLAGS_history_cutoff_propagation_interval_ms) * 1ms;

  return EffectiveHistoryCutoff();
}

HybridTime TabletRetentionPolicy::EffectiveHistoryCutoff() {
  return SanitizeHistoryCutoff(ClockBasedHistoryCutoff(clock_.get()));
}

HybridTime TabletRetentionPolicy::SanitizeHistoryCutoff(HybridTime proposed_cutoff) {
  HybridTime allowed_cutoff;
  if (active_readers_.empty()) {
    // There are no readers restricting our garbage collection of old records.
    allowed_cutoff = proposed_cutoff;
  } else {
    // Cannot garbage-collect any records that are still being read.
    allowed_cutoff = std::min(proposed_cutoff, *active_readers_.begin());
  }

  HybridTime provided_allowed_cutoff;
  if (allowed_history_cutoff_provider_) {
    provided_allowed_cutoff = allowed_history_cutoff_provider_(&metadata_);
    allowed_cutoff = std::min(provided_allowed_cutoff, allowed_cutoff);
    VLOG_WITH_PREFIX(2) << __func__ << ", cutoff from the provider " << allowed_cutoff;
  }

  if (metadata_.table_id() == kObsoleteShortPrimaryTableId) {
    auto syscatalog_history_retention_interval_sec = ANNOTATE_UNPROTECTED_READ(
        FLAGS_timestamp_syscatalog_history_retention_interval_sec);
    if (syscatalog_history_retention_interval_sec) {
      allowed_cutoff = std::min(
          allowed_cutoff, clock_->Now().AddSeconds(-syscatalog_history_retention_interval_sec));
    }
  }

  VLOG_WITH_PREFIX(4) << __func__ << ", result: " << allowed_cutoff
                      << ", active readers: " << active_readers_.size()
                      << ", provided_allowed_cutoff: " << provided_allowed_cutoff
                      << ", schedules: " << AsString(metadata_.SnapshotSchedules());

  return allowed_cutoff;
}

void TabletRetentionPolicy::EnableHistoryCutoffPropagation(bool value) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (value) {
    --disable_counter_;
  } else {
    ++disable_counter_;
  }
}

}  // namespace tablet
}  // namespace yb
