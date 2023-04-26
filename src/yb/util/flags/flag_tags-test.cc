// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
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

#include <unordered_set>

#include <gtest/gtest.h>

#include "yb/gutil/map-util.h"
#include "yb/util/flags.h"
#include "yb/util/test_util.h"

DEFINE_NON_RUNTIME_int32(flag_with_no_tags, 0, "test flag that has no tags");

DEFINE_NON_RUNTIME_int32(flag_with_one_tag, 0, "test flag that has 1 tag");
TAG_FLAG(flag_with_one_tag, stable);

DEFINE_NON_RUNTIME_int32(flag_with_two_tags, 0, "test flag that has 2 tags");
TAG_FLAG(flag_with_two_tags, evolving);
TAG_FLAG(flag_with_two_tags, unsafe);

using std::string;
using std::unordered_set;

namespace yb {

class FlagTagsTest : public YBTest {};

TEST_F(FlagTagsTest, TestTags) {
  unordered_set<FlagTag> tags;
  GetFlagTags("flag_with_no_tags", &tags);
  EXPECT_EQ(0, tags.size());

  GetFlagTags("flag_with_one_tag", &tags);
  EXPECT_EQ(1, tags.size());
  EXPECT_TRUE(ContainsKey(tags, FlagTag::kStable));

  GetFlagTags("flag_with_two_tags", &tags);
  EXPECT_EQ(2, tags.size());
  EXPECT_TRUE(ContainsKey(tags, FlagTag::kEvolving));
  EXPECT_TRUE(ContainsKey(tags, FlagTag::kUnsafe));

  GetFlagTags("missing_flag", &tags);
  EXPECT_EQ(0, tags.size());
}

}  // namespace yb
