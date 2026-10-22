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

import org.apache.amoro.Action;
import org.apache.amoro.TableRuntime;

/** Runtime state helpers for Paimon maintenance. */
public final class PaimonMaintenance {

  public static final StateKey<PaimonMaintenanceState> STATE_KEY =
      StateKey.stateKey("paimon_maintenance_state")
          .jsonType(PaimonMaintenanceState.class)
          .defaultValue(new PaimonMaintenanceState());

  private PaimonMaintenance() {}

  public static PaimonMaintenanceState state(TableRuntime tableRuntime) {
    PaimonMaintenanceState state = tableRuntime.getState(STATE_KEY);
    return state == null ? new PaimonMaintenanceState() : state;
  }

  public static void requestManual(TableRuntime tableRuntime, Action action) {
    long requestedAt = System.currentTimeMillis();
    tableRuntime.updateState(
        STATE_KEY,
        current -> {
          PaimonMaintenanceState state = current == null ? new PaimonMaintenanceState() : current;
          return state.addManualAction(action.getName(), requestedAt);
        });
  }

  public static void clearManual(TableRuntime tableRuntime, Action action) {
    tableRuntime.updateState(
        STATE_KEY,
        current -> {
          PaimonMaintenanceState state = current == null ? new PaimonMaintenanceState() : current;
          return state.clearManualAction(action.getName());
        });
  }

  public static void stamp(TableRuntime tableRuntime, Action action) {
    long now = System.currentTimeMillis();
    tableRuntime.updateState(
        STATE_KEY,
        current -> {
          PaimonMaintenanceState state = current == null ? new PaimonMaintenanceState() : current;
          return state.stamp(action.getName(), now);
        });
  }
}
