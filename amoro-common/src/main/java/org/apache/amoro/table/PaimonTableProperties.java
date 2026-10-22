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

package org.apache.amoro.table;

/** Amoro-owned Paimon table options that gate scheduled maintenance. */
public final class PaimonTableProperties {

  public static final String GC_ENABLED = "gc.enabled";
  public static final boolean GC_ENABLED_DEFAULT = true;

  public static final String PARTIAL_COMPACT_ENABLED = "maintenance.partial-compact.enabled";
  public static final boolean PARTIAL_COMPACT_ENABLED_DEFAULT = false;
  public static final String PARTIAL_COMPACT_INTERVAL = "maintenance.partial-compact.interval";

  public static final String FULL_COMPACT_ENABLED = "maintenance.full-compact.enabled";
  public static final boolean FULL_COMPACT_ENABLED_DEFAULT = false;
  public static final String FULL_COMPACT_INTERVAL = "maintenance.full-compact.interval";

  public static final String CLEAN_ORPHAN_ENABLED = "maintenance.clean-orphan-files.enabled";
  public static final boolean CLEAN_ORPHAN_ENABLED_DEFAULT = false;
  public static final String CLEAN_ORPHAN_MIN_EXISTING_TIME =
      "maintenance.clean-orphan-files.min-existing-time";
  public static final String CLEAN_ORPHAN_MIN_EXISTING_TIME_DEFAULT = "2d";

  public static final String EXPIRE_SNAPSHOTS_ENABLED = "maintenance.expire-snapshots.enabled";
  public static final boolean EXPIRE_SNAPSHOTS_ENABLED_DEFAULT = false;

  public static final String EXPIRE_PARTITIONS_ENABLED = "maintenance.expire-partitions.enabled";
  public static final boolean EXPIRE_PARTITIONS_ENABLED_DEFAULT = false;

  private PaimonTableProperties() {}
}
