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

package org.apache.amoro;

/** Maintenance actions for Paimon tables. Names are distinct from Iceberg actions. */
public final class PaimonActions {

  public static final Action PARTIAL_COMPACT = Action.register("partial-compact");
  public static final Action FULL_COMPACT = Action.register("full-compact");
  public static final Action CLEAN_ORPHAN = Action.register("clean-orphan-files-paimon");
  public static final Action EXPIRE_SNAPSHOTS = Action.register("expire-snapshots-paimon");
  public static final Action EXPIRE_PARTITIONS = Action.register("expire-partitions-paimon");

  private PaimonActions() {}

  public static boolean isCompact(Action action) {
    return PARTIAL_COMPACT.equals(action) || FULL_COMPACT.equals(action);
  }

  public static boolean isCleanup(Action action) {
    return CLEAN_ORPHAN.equals(action)
        || EXPIRE_SNAPSHOTS.equals(action)
        || EXPIRE_PARTITIONS.equals(action);
  }

  public static boolean isMaintenance(Action action) {
    return isCompact(action) || isCleanup(action);
  }
}
