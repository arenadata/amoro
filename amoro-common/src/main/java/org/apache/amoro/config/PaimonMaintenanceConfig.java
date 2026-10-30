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

import org.apache.amoro.Action;
import org.apache.amoro.PaimonActions;
import org.apache.amoro.shade.guava32.com.google.common.base.Objects;
import org.apache.amoro.table.PaimonTableProperties;

import java.util.Map;

/**
 * Per-table gates for Paimon maintenance. Retention thresholds that Paimon already owns
 * (snapshot.num-retained.*, partition.expiration-time, compaction.min.file-num) stay on the native
 * options and are not copied here.
 */
public class PaimonMaintenanceConfig {

  private boolean partialCompactEnabled = PaimonTableProperties.PARTIAL_COMPACT_ENABLED_DEFAULT;
  private Long partialCompactIntervalMillis;
  private boolean fullCompactEnabled = PaimonTableProperties.FULL_COMPACT_ENABLED_DEFAULT;
  private Long fullCompactIntervalMillis;
  private boolean cleanOrphanEnabled = PaimonTableProperties.CLEAN_ORPHAN_ENABLED_DEFAULT;
  private long orphanMinExistingMillis =
      ConfigHelpers.TimeUtils.parseDuration(
              PaimonTableProperties.CLEAN_ORPHAN_MIN_EXISTING_TIME_DEFAULT)
          .toMillis();
  private boolean expireSnapshotsEnabled = PaimonTableProperties.EXPIRE_SNAPSHOTS_ENABLED_DEFAULT;
  private boolean expirePartitionsEnabled = PaimonTableProperties.EXPIRE_PARTITIONS_ENABLED_DEFAULT;

  public static PaimonMaintenanceConfig defaults() {
    return new PaimonMaintenanceConfig();
  }

  public static PaimonMaintenanceConfig parse(Map<String, String> properties) {
    PaimonMaintenanceConfig config = new PaimonMaintenanceConfig();
    if (properties == null || properties.isEmpty()) {
      return config;
    }
    boolean gcEnabled = bool(properties, PaimonTableProperties.GC_ENABLED, true);
    config.partialCompactEnabled =
        bool(
            properties,
            PaimonTableProperties.PARTIAL_COMPACT_ENABLED,
            PaimonTableProperties.PARTIAL_COMPACT_ENABLED_DEFAULT);
    config.partialCompactIntervalMillis =
        interval(properties, PaimonTableProperties.PARTIAL_COMPACT_INTERVAL);
    config.fullCompactEnabled =
        bool(
            properties,
            PaimonTableProperties.FULL_COMPACT_ENABLED,
            PaimonTableProperties.FULL_COMPACT_ENABLED_DEFAULT);
    config.fullCompactIntervalMillis =
        interval(properties, PaimonTableProperties.FULL_COMPACT_INTERVAL);
    config.cleanOrphanEnabled =
        gcEnabled
            && bool(
                properties,
                PaimonTableProperties.CLEAN_ORPHAN_ENABLED,
                PaimonTableProperties.CLEAN_ORPHAN_ENABLED_DEFAULT);
    config.orphanMinExistingMillis =
        ConfigHelpers.TimeUtils.parseDuration(
                string(
                    properties,
                    PaimonTableProperties.CLEAN_ORPHAN_MIN_EXISTING_TIME,
                    PaimonTableProperties.CLEAN_ORPHAN_MIN_EXISTING_TIME_DEFAULT))
            .toMillis();
    config.expireSnapshotsEnabled =
        gcEnabled
            && bool(
                properties,
                PaimonTableProperties.EXPIRE_SNAPSHOTS_ENABLED,
                PaimonTableProperties.EXPIRE_SNAPSHOTS_ENABLED_DEFAULT);
    config.expirePartitionsEnabled =
        gcEnabled
            && bool(
                properties,
                PaimonTableProperties.EXPIRE_PARTITIONS_ENABLED,
                PaimonTableProperties.EXPIRE_PARTITIONS_ENABLED_DEFAULT);
    return config;
  }

  public boolean enabled(Action action) {
    if (PaimonActions.PARTIAL_COMPACT.equals(action)) {
      return partialCompactEnabled;
    }
    if (PaimonActions.FULL_COMPACT.equals(action)) {
      return fullCompactEnabled;
    }
    if (PaimonActions.CLEAN_ORPHAN.equals(action)) {
      return cleanOrphanEnabled;
    }
    if (PaimonActions.EXPIRE_SNAPSHOTS.equals(action)) {
      return expireSnapshotsEnabled;
    }
    if (PaimonActions.EXPIRE_PARTITIONS.equals(action)) {
      return expirePartitionsEnabled;
    }
    return false;
  }

  /** Empty table option means the plugin interval is used. */
  public Long intervalOverrideMillis(Action action) {
    if (PaimonActions.PARTIAL_COMPACT.equals(action)) {
      return partialCompactIntervalMillis;
    }
    if (PaimonActions.FULL_COMPACT.equals(action)) {
      return fullCompactIntervalMillis;
    }
    return null;
  }

  public boolean isPartialCompactEnabled() {
    return partialCompactEnabled;
  }

  public Long getPartialCompactIntervalMillis() {
    return partialCompactIntervalMillis;
  }

  public boolean isFullCompactEnabled() {
    return fullCompactEnabled;
  }

  public Long getFullCompactIntervalMillis() {
    return fullCompactIntervalMillis;
  }

  public boolean isCleanOrphanEnabled() {
    return cleanOrphanEnabled;
  }

  public long getOrphanMinExistingMillis() {
    return orphanMinExistingMillis;
  }

  public boolean isExpireSnapshotsEnabled() {
    return expireSnapshotsEnabled;
  }

  public boolean isExpirePartitionsEnabled() {
    return expirePartitionsEnabled;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    PaimonMaintenanceConfig that = (PaimonMaintenanceConfig) o;
    return partialCompactEnabled == that.partialCompactEnabled
        && fullCompactEnabled == that.fullCompactEnabled
        && cleanOrphanEnabled == that.cleanOrphanEnabled
        && orphanMinExistingMillis == that.orphanMinExistingMillis
        && expireSnapshotsEnabled == that.expireSnapshotsEnabled
        && expirePartitionsEnabled == that.expirePartitionsEnabled
        && Objects.equal(partialCompactIntervalMillis, that.partialCompactIntervalMillis)
        && Objects.equal(fullCompactIntervalMillis, that.fullCompactIntervalMillis);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(
        partialCompactEnabled,
        partialCompactIntervalMillis,
        fullCompactEnabled,
        fullCompactIntervalMillis,
        cleanOrphanEnabled,
        orphanMinExistingMillis,
        expireSnapshotsEnabled,
        expirePartitionsEnabled);
  }

  private static boolean bool(Map<String, String> properties, String key, boolean defaultValue) {
    String raw = string(properties, key, null);
    if (raw == null) {
      return defaultValue;
    }
    return Boolean.parseBoolean(raw);
  }

  private static String string(Map<String, String> properties, String key, String defaultValue) {
    if (properties == null) {
      return defaultValue;
    }
    String raw = properties.get(key);
    if (raw == null || raw.trim().isEmpty()) {
      return defaultValue;
    }
    return raw.trim();
  }

  private static Long interval(Map<String, String> properties, String key) {
    String raw = string(properties, key, null);
    if (raw == null) {
      return null;
    }
    return ConfigHelpers.TimeUtils.parseDuration(raw).toMillis();
  }
}
