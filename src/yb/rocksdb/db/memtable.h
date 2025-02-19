//  Copyright (c) 2011-present, Facebook, Inc.  All rights reserved.
//  This source code is licensed under the BSD-style license found in the
//  LICENSE file in the root directory of this source tree. An additional grant
//  of patent rights can be found in the PATENTS file in the same directory.
//
// The following only applies to changes made to this file as part of YugaByte development.
//
// Portions Copyright (c) YugaByte, Inc.
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
// Copyright (c) 2011 The LevelDB Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file. See the AUTHORS file for names of contributors.


#pragma once

#include <atomic>
#include <deque>
#include <functional>
#include <memory>
#include <string>
#include <vector>

#include "yb/rocksdb/db.h"
#include "yb/rocksdb/db/dbformat.h"
#include "yb/rocksdb/db/file_numbers.h"
#include "yb/rocksdb/db/memtable_allocator.h"
#include "yb/rocksdb/db/version_edit.h"
#include "yb/rocksdb/env.h"
#include "yb/rocksdb/immutable_options.h"
#include "yb/rocksdb/memtablerep.h"
#include "yb/rocksdb/util/concurrent_arena.h"
#include "yb/rocksdb/util/dynamic_bloom.h"
#include "yb/rocksdb/util/mutable_cf_options.h"

namespace yb {

class MemTracker;

}

namespace rocksdb {

class Mutex;
class MemTableIterator;
class MergeContext;
class WriteBuffer;
class InternalIterator;

struct MemTableOptions {
  explicit MemTableOptions(
      const ImmutableCFOptions& ioptions,
      const MutableCFOptions& mutable_cf_options);
  size_t write_buffer_size;
  size_t arena_block_size;
  uint32_t memtable_prefix_bloom_bits;
  uint32_t memtable_prefix_bloom_probes;
  size_t memtable_prefix_bloom_huge_page_tlb_size;
  bool inplace_update_support;
  size_t inplace_update_num_locks;
  UpdateStatus (*inplace_callback)(char* existing_value,
                                   uint32_t* existing_value_size,
                                   Slice delta_value,
                                   std::string* merged_value);
  size_t max_successive_merges;
  bool filter_deletes;
  Statistics* statistics;
  MergeOperator* merge_operator;
  Logger* info_log;
  std::shared_ptr<yb::MemTracker> mem_tracker;
};

YB_DEFINE_ENUM(FlushState, (kNotRequested)(kRequested)(kScheduled));

struct PreparedAdd {
  SequenceNumber min_seq_no = 0;
  size_t total_encoded_len = 0;
  size_t num_deletes = 0;
  Slice last_key;
  Slice last_value;
};

// Note:  Many of the methods in this class have comments indicating that
// external synchromization is required as these methods are not thread-safe.
// It is up to higher layers of code to decide how to prevent concurrent
// invokation of these methods.  This is usually done by acquiring either
// the db mutex or the single writer thread.
//
// Some of these methods are documented to only require external
// synchronization if this memtable is immutable.  Calling MarkImmutable() is
// not sufficient to guarantee immutability.  It is up to higher layers of
// code to determine if this MemTable can still be modified by other threads.
// Eg: The Superversion stores a pointer to the current MemTable (that can
// be modified) and a separate list of the MemTables that can no longer be
// written to (aka the 'immutable memtables').
class MemTable {
 public:
  struct KeyComparator : public MemTableRep::KeyComparator {
    const InternalKeyComparator comparator;
    explicit KeyComparator(const InternalKeyComparator& c) : comparator(c) { }
    virtual int operator()(const char* prefix_len_key1,
                           const char* prefix_len_key2) const override;
    virtual int operator()(const char* prefix_len_key,
                           const Slice& key) const override;
  };

  // MemTables are reference counted.  The initial reference count
  // is zero and the caller must call Ref() at least once.
  //
  // earliest_seq should be the current SequenceNumber in the db such that any
  // key inserted into this memtable will have an equal or larger seq number.
  // (When a db is first created, the earliest sequence number will be 0).
  // If the earliest sequence number is not known, kMaxSequenceNumber may be
  // used, but this may prevent some transactions from succeeding until the
  // first key is inserted into the memtable.
  explicit MemTable(const InternalKeyComparator& comparator,
                    const ImmutableCFOptions& ioptions,
                    const MutableCFOptions& mutable_cf_options,
                    WriteBuffer* write_buffer, SequenceNumber earliest_seq);

  // Do not delete this MemTable unless Unref() indicates it not in use.
  ~MemTable();

  // Increase reference count.
  // REQUIRES: external synchronization to prevent simultaneous
  // operations on the same MemTable.
  void Ref() { ++refs_; }

  // Drop reference count.
  // If the refcount goes to zero return this memtable, otherwise return null.
  // REQUIRES: external synchronization to prevent simultaneous
  // operations on the same MemTable.
  MemTable* Unref() {
    --refs_;
    DCHECK_GE(refs_, 0);
    if (refs_ <= 0) {
      return this;
    }
    return nullptr;
  }

  // Returns an estimate of the number of bytes of data in use by this
  // data structure.
  //
  // REQUIRES: external synchronization to prevent simultaneous
  // operations on the same MemTable (unless this Memtable is immutable).
  size_t ApproximateMemoryUsage();

  // This method heuristically determines if the memtable should continue to
  // host more data.
  bool ShouldScheduleFlush() const {
    return flush_state_.load(std::memory_order_relaxed) == FlushState::kRequested;
  }

  // Returns true if a flush should be scheduled and the caller should
  // be the one to schedule it
  bool MarkFlushScheduled() {
    auto before = FlushState::kRequested;
    return flush_state_.compare_exchange_strong(before,
                                                FlushState::kScheduled,
                                                std::memory_order_relaxed,
                                                std::memory_order_relaxed);
  }

  // Return an iterator that yields the contents of the memtable.
  //
  // The caller must ensure that the underlying MemTable remains live
  // while the returned iterator is live.  The keys returned by this
  // iterator are internal keys encoded by AppendInternalKey in the
  // db/dbformat.{h,cc} module.
  //
  // By default, it returns an iterator for prefix seek if prefix_extractor
  // is configured in Options.
  // arena: If not null, the arena needs to be used to allocate the Iterator.
  //        Calling ~Iterator of the iterator will destroy all the states but
  //        those allocated in arena.
  InternalIterator* NewIterator(const ReadOptions& read_options, Arena* arena);

  // Add an entry into memtable that maps key to value at the
  // specified sequence number and with the specified type.
  // Typically value will be empty if type==kTypeDeletion.
  //
  // REQUIRES: if allow_concurrent = false, external synchronization to prevent
  // simultaneous operations on the same MemTable.
  void Add(SequenceNumber seq, ValueType type, const SliceParts& key,
           const SliceParts& value, bool allow_concurrent = false);

  KeyHandle PrepareAdd(
      SequenceNumber s, ValueType type, const SliceParts& key, const SliceParts& value,
      PreparedAdd* prepared_add);

  void ApplyPreparedAdd(
      const KeyHandle* handle, size_t count, const PreparedAdd& prepared_add,
      bool allow_concurrent);

  // If memtable contains a value for key, store it in *value and return true.
  // If memtable contains a deletion for key, store a NotFound() error
  // in *status and return true.
  // If memtable contains Merge operation as the most recent entry for a key,
  //   and the merge process does not stop (not reaching a value or delete),
  //   prepend the current merge operand to *operands.
  //   store MergeInProgress in s, and return false.
  // Else, return false.
  // If any operation was found, its most recent sequence number
  // will be stored in *seq on success (regardless of whether true/false is
  // returned).  Otherwise, *seq will be set to kMaxSequenceNumber.
  // On success, *s may be set to OK, NotFound, or MergeInProgress.  Any other
  // status returned indicates a corruption or other unexpected error.
  bool Get(const LookupKey& key, std::string* value, Status* s,
           MergeContext* merge_context, SequenceNumber* seq);

  bool Get(const LookupKey& key, std::string* value, Status* s,
           MergeContext* merge_context) {
    SequenceNumber seq;
    return Get(key, value, s, merge_context, &seq);
  }

  // Attempts to update the new_value inplace, else does normal Add
  // Pseudocode
  //   if key exists in current memtable && prev_value is of type kTypeValue
  //     if new sizeof(new_value) <= sizeof(prev_value)
  //       update inplace
  //     else add(key, new_value)
  //   else add(key, new_value)
  //
  // REQUIRES: external synchronization to prevent simultaneous
  // operations on the same MemTable.
  void Update(SequenceNumber seq,
              const Slice& key,
              const Slice& value);

  bool Erase(const Slice& key);

  // If prev_value for key exists, attempts to update it inplace.
  // else returns false
  // Pseudocode
  //   if key exists in current memtable && prev_value is of type kTypeValue
  //     new_value = delta(prev_value)
  //     if sizeof(new_value) <= sizeof(prev_value)
  //       update inplace
  //     else add(key, new_value)
  //   else return false
  //
  // REQUIRES: external synchronization to prevent simultaneous
  // operations on the same MemTable.
  bool UpdateCallback(SequenceNumber seq,
                      const Slice& key,
                      const Slice& delta);

  // Returns the number of successive merge entries starting from the newest
  // entry for the key up to the last non-merge entry or last entry for the
  // key in the memtable.
  size_t CountSuccessiveMergeEntries(const LookupKey& key);

  // Get total number of entries in the mem table.
  // REQUIRES: external synchronization to prevent simultaneous
  // operations on the same MemTable (unless this Memtable is immutable).
  uint64_t num_entries() const {
    return num_entries_.load(std::memory_order_relaxed);
  }

  // Get total number of deletes in the mem table.
  // REQUIRES: external synchronization to prevent simultaneous
  // operations on the same MemTable (unless this Memtable is immutable).
  uint64_t num_deletes() const {
    return num_deletes_.load(std::memory_order_relaxed);
  }

  // Returns the edits area that is needed for flushing the memtable
  VersionEdit* GetEdits() { return &edit_; }

  // Returns if there is no entry inserted to the mem table.
  // REQUIRES: external synchronization to prevent simultaneous
  // operations on the same MemTable (unless this Memtable is immutable).
  bool IsEmpty() const { return first_seqno_ == 0; }

  // Returns the sequence number of the first element that was inserted
  // into the memtable.
  // REQUIRES: external synchronization to prevent simultaneous
  // operations on the same MemTable (unless this Memtable is immutable).
  SequenceNumber GetFirstSequenceNumber() const {
    return first_seqno_.load(std::memory_order_relaxed);
  }

  // Returns the sequence number that is guaranteed to be smaller than or equal
  // to the sequence number of any key that could be inserted into this
  // memtable. It can then be assumed that any write with a larger(or equal)
  // sequence number will be present in this memtable or a later memtable.
  //
  // If the earliest sequence number could not be determined,
  // kMaxSequenceNumber will be returned.
  SequenceNumber GetEarliestSequenceNumber() const {
    return earliest_seqno_.load(std::memory_order_relaxed);
  }

  // Returns the next active logfile number when this memtable is about to
  // be flushed to storage
  // REQUIRES: external synchronization to prevent simultaneous
  // operations on the same MemTable.
  uint64_t GetNextLogNumber() const { return mem_next_logfile_number_; }

  // Sets the next active logfile number when this memtable is about to
  // be flushed to storage
  // REQUIRES: external synchronization to prevent simultaneous
  // operations on the same MemTable.
  void SetNextLogNumber(uint64_t num) { mem_next_logfile_number_ = num; }

  void SetFlushStartTime(std::chrono::steady_clock::time_point value) {
    flush_start_time_ = value;
  }

  std::chrono::steady_clock::time_point FlushStartTime() const {
    return flush_start_time_;
  }

  // Notify the underlying storage that no more items will be added.
  // REQUIRES: external synchronization to prevent simultaneous
  // operations on the same MemTable.
  // After MarkImmutable() is called, you should not attempt to
  // write anything to this MemTable().  (Ie. do not call Add() or Update()).
  void MarkImmutable() {
    table_->MarkReadOnly();
    allocator_.DoneAllocating();
  }

  // return true if the current MemTableRep supports merge operator.
  bool IsMergeOperatorSupported() const {
    return table_->IsMergeOperatorSupported();
  }

  // return true if the current MemTableRep supports snapshots.
  // inplace update prevents snapshots,
  bool IsSnapshotSupported() const {
    return table_->IsSnapshotSupported() && !moptions_.inplace_update_support;
  }

  uint64_t ApproximateSize(const Slice& start_ikey, const Slice& end_ikey);

  // Get the lock associated for the key
  port::RWMutex* GetLock(const Slice& key);

  const InternalKeyComparator& GetInternalKeyComparator() const {
    return comparator_.comparator;
  }

  const MemTableOptions* GetMemTableOptions() const { return &moptions_; }

  void UpdateFrontiers(const UserFrontiers& value) {
    std::lock_guard<SpinMutex> l(frontiers_mutex_);
    if (frontiers_) {
      frontiers_->MergeFrontiers(value);
    } else {
      frontiers_ = value.Clone();
    }
  }

  UserFrontierPtr GetFrontier(UpdateUserValueType type) const;

  const UserFrontiers* Frontiers() const { return frontiers_.get(); }

  std::string ToString() const;

  bool FullyErased() const {
    return num_entries_.load(std::memory_order_acquire) ==
           num_erased_.load(std::memory_order_acquire);
  }

 private:

  friend class MemTableIterator;
  friend class MemTableBackwardIterator;
  friend class MemTableList;

  KeyComparator comparator_;
  const MemTableOptions moptions_;
  int refs_;
  const size_t kArenaBlockSize;
  ConcurrentArena arena_;
  MemTableAllocator allocator_;
  std::unique_ptr<MemTableRep> table_;

  // Total data size of all data inserted
  std::atomic<uint64_t> data_size_;
  std::atomic<uint64_t> num_entries_;
  std::atomic<uint64_t> num_deletes_;
  std::atomic<uint64_t> num_erased_{0};

  // These are used to manage memtable flushes to storage
  bool flush_in_progress_;        // started the flush
  bool flush_completed_;          // finished the flush
  uint64_t file_number_;          // filled up after flush is complete
  // Filled up after flush is complete to prevent file from being deleted util it is added into the
  // VersionSet.
  FileNumbersHolder file_number_holder_;

  // The updates to be applied to the transaction log when this
  // memtable is flushed to storage.
  VersionEdit edit_;

  // The sequence number of the kv that was inserted first
  std::atomic<SequenceNumber> first_seqno_;

  // The db sequence number at the time of creation or kMaxSequenceNumber
  // if not set.
  std::atomic<SequenceNumber> earliest_seqno_;

  // The log files earlier than this number can be deleted.
  uint64_t mem_next_logfile_number_;

  std::chrono::steady_clock::time_point flush_start_time_;

  // rw locks for inplace updates
  std::vector<port::RWMutex> locks_;

  const SliceTransform* const prefix_extractor_;
  std::unique_ptr<DynamicBloom> prefix_bloom_;

  std::atomic<FlushState> flush_state_;

  Env* env_;

  mutable SpinMutex frontiers_mutex_;
  std::unique_ptr<UserFrontiers> frontiers_;

  // Returns a heuristic flush decision
  bool ShouldFlushNow() const;

  // Updates flush_state_ using ShouldFlushNow()
  void UpdateFlushState();

  std::vector<char> erase_key_buffer_;

  // No copying allowed
  MemTable(const MemTable&) = delete;
  MemTable& operator=(const MemTable&) = delete;
};

extern const char* EncodeKey(std::string* scratch, const Slice& target);

}  // namespace rocksdb
