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

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Last-run timestamps and one-shot manual requests for Paimon maintenance. */
public class PaimonMaintenanceState {

  private Map<String, Long> lastRunAt = new LinkedHashMap<>();
  private Set<String> manualActions = new LinkedHashSet<>();
  private long manualRequestedAt;

  public Map<String, Long> getLastRunAt() {
    if (lastRunAt == null) {
      lastRunAt = new LinkedHashMap<>();
    }
    return lastRunAt;
  }

  public void setLastRunAt(Map<String, Long> lastRunAt) {
    this.lastRunAt = lastRunAt == null ? new LinkedHashMap<>() : new LinkedHashMap<>(lastRunAt);
  }

  public long lastRunAt(String actionName) {
    Long value = getLastRunAt().get(actionName);
    return value == null ? 0L : value;
  }

  public PaimonMaintenanceState stamp(String actionName, long timeMillis) {
    getLastRunAt().put(actionName, timeMillis);
    return this;
  }

  public Set<String> getManualActions() {
    if (manualActions == null) {
      manualActions = new LinkedHashSet<>();
    }
    return manualActions;
  }

  public void setManualActions(Set<String> manualActions) {
    this.manualActions = new LinkedHashSet<>();
    if (manualActions != null) {
      this.manualActions.addAll(manualActions);
    }
  }

  public long getManualRequestedAt() {
    return manualRequestedAt;
  }

  public void setManualRequestedAt(long manualRequestedAt) {
    this.manualRequestedAt = manualRequestedAt;
  }

  public boolean hasManualAction(String actionName) {
    return actionName != null && getManualActions().contains(actionName);
  }

  /** Adds {@code actionName} once. A second add keeps the original request time. */
  public PaimonMaintenanceState addManualAction(String actionName, long requestedAt) {
    if (getManualActions().add(actionName)) {
      this.manualRequestedAt = requestedAt;
    }
    return this;
  }

  public PaimonMaintenanceState clearManualAction(String actionName) {
    if (manualActions != null && manualActions.remove(actionName) && manualActions.isEmpty()) {
      this.manualRequestedAt = 0L;
    }
    return this;
  }
}
