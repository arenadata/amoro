/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.amoro.config;

import org.apache.amoro.PaimonActions;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

public class TestPaimonMaintenanceConfig {

  @Test
  public void defaultsAreOffAndGcDoesNotEnableThem() {
    PaimonMaintenanceConfig config = PaimonMaintenanceConfig.parse(new HashMap<>());
    Assertions.assertFalse(config.enabled(PaimonActions.PARTIAL_COMPACT));
    Assertions.assertFalse(config.enabled(PaimonActions.FULL_COMPACT));
    Assertions.assertFalse(config.enabled(PaimonActions.CLEAN_ORPHAN));
    Assertions.assertFalse(config.enabled(PaimonActions.EXPIRE_SNAPSHOTS));
    Assertions.assertFalse(config.enabled(PaimonActions.EXPIRE_PARTITIONS));
    Assertions.assertNull(config.intervalOverrideMillis(PaimonActions.PARTIAL_COMPACT));
    Assertions.assertEquals(2L * 24 * 60 * 60 * 1000, config.getOrphanMinExistingMillis());
  }

  @Test
  public void gcEnabledTurnsOffCleanupButNotCompaction() {
    Map<String, String> properties = new HashMap<>();
    properties.put("gc.enabled", "false");
    properties.put("maintenance.expire-snapshots.enabled", "true");
    properties.put("maintenance.clean-orphan-files.enabled", "true");
    properties.put("maintenance.expire-partitions.enabled", "true");
    properties.put("maintenance.partial-compact.enabled", "true");
    properties.put("maintenance.partial-compact.interval", "15min");
    PaimonMaintenanceConfig config = PaimonMaintenanceConfig.parse(properties);
    Assertions.assertFalse(config.isExpireSnapshotsEnabled());
    Assertions.assertFalse(config.isCleanOrphanEnabled());
    Assertions.assertFalse(config.isExpirePartitionsEnabled());
    Assertions.assertTrue(config.isPartialCompactEnabled());
    Assertions.assertEquals(15L * 60 * 1000, config.getPartialCompactIntervalMillis());
  }
}
