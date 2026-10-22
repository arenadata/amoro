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

package org.apache.amoro.formats.paimon.maintenance;

import org.apache.amoro.Action;
import org.apache.amoro.PaimonActions;
import org.apache.amoro.TableFormat;
import org.apache.amoro.TableRuntime;
import org.apache.amoro.config.ConfigOption;
import org.apache.amoro.config.ConfigOptions;
import org.apache.amoro.config.Configurations;
import org.apache.amoro.config.PaimonMaintenanceConfig;
import org.apache.amoro.process.ExecuteEngine;
import org.apache.amoro.process.LocalExecutionEngine;
import org.apache.amoro.process.ProcessFactory;
import org.apache.amoro.process.ProcessTriggerStrategy;
import org.apache.amoro.process.RecoverProcessFailedException;
import org.apache.amoro.process.TableProcess;
import org.apache.amoro.process.TableProcessStore;
import org.apache.amoro.shade.guava32.com.google.common.collect.Maps;
import org.apache.amoro.table.PaimonMaintenance;
import org.apache.amoro.table.PaimonMaintenanceState;
import org.apache.amoro.table.StateKey;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Schedules Paimon maintenance the same way Iceberg cleanup is scheduled. */
public class PaimonProcessFactory implements ProcessFactory {

  public static final String PLUGIN_NAME = "paimon";

  public static final ConfigOption<Boolean> PARTIAL_COMPACT_ENABLED =
      ConfigOptions.key("partial-compact.enabled").booleanType().defaultValue(true);
  public static final ConfigOption<Duration> PARTIAL_COMPACT_INTERVAL =
      ConfigOptions.key("partial-compact.interval")
          .durationType()
          .defaultValue(Duration.ofHours(1));

  public static final ConfigOption<Boolean> FULL_COMPACT_ENABLED =
      ConfigOptions.key("full-compact.enabled").booleanType().defaultValue(true);
  public static final ConfigOption<Duration> FULL_COMPACT_INTERVAL =
      ConfigOptions.key("full-compact.interval").durationType().defaultValue(Duration.ofDays(1));

  public static final ConfigOption<Boolean> ORPHAN_CLEAN_ENABLED =
      ConfigOptions.key("clean-orphan-files-paimon.enabled").booleanType().defaultValue(true);
  public static final ConfigOption<Duration> ORPHAN_CLEAN_INTERVAL =
      ConfigOptions.key("clean-orphan-files-paimon.interval")
          .durationType()
          .defaultValue(Duration.ofDays(1));

  public static final ConfigOption<Boolean> EXPIRE_SNAPSHOTS_ENABLED =
      ConfigOptions.key("expire-snapshots-paimon.enabled").booleanType().defaultValue(true);
  public static final ConfigOption<Duration> EXPIRE_SNAPSHOTS_INTERVAL =
      ConfigOptions.key("expire-snapshots-paimon.interval")
          .durationType()
          .defaultValue(Duration.ofHours(1));

  public static final ConfigOption<Boolean> EXPIRE_PARTITIONS_ENABLED =
      ConfigOptions.key("expire-partitions-paimon.enabled").booleanType().defaultValue(true);
  public static final ConfigOption<Duration> EXPIRE_PARTITIONS_INTERVAL =
      ConfigOptions.key("expire-partitions-paimon.interval")
          .durationType()
          .defaultValue(Duration.ofDays(1));

  private final Map<Action, ProcessTriggerStrategy> actions = Maps.newHashMap();
  private ExecuteEngine localEngine;

  @Override
  public List<StateKey<?>> requiredStates() {
    return Collections.singletonList(PaimonMaintenance.STATE_KEY);
  }

  @Override
  public void availableExecuteEngines(Collection<ExecuteEngine> allAvailableEngines) {
    for (ExecuteEngine engine : allAvailableEngines) {
      if (engine instanceof LocalExecutionEngine) {
        this.localEngine = engine;
      }
    }
  }

  @Override
  public Map<TableFormat, Set<Action>> supportedActions() {
    if (actions.isEmpty()) {
      return Collections.emptyMap();
    }
    return Collections.singletonMap(TableFormat.PAIMON, actions.keySet());
  }

  @Override
  public ProcessTriggerStrategy triggerStrategy(TableFormat format, Action action) {
    return actions.getOrDefault(action, ProcessTriggerStrategy.METADATA_TRIGGER);
  }

  @Override
  public Optional<TableProcess> trigger(TableRuntime tableRuntime, Action action) {
    if (!readyToRun(tableRuntime, action)) {
      return Optional.empty();
    }
    return Optional.of(newProcess(tableRuntime, action));
  }

  @Override
  public TableProcess recover(TableRuntime tableRuntime, TableProcessStore store)
      throws RecoverProcessFailedException {
    if (localEngine == null) {
      throw new RecoverProcessFailedException(
          "Local execution engine is not available for PaimonProcessFactory");
    }
    Action action = store.getAction();
    if (!actions.containsKey(action)) {
      throw new RecoverProcessFailedException(
          "Unsupported action for PaimonProcessFactory: " + action);
    }
    return newProcess(tableRuntime, action);
  }

  @Override
  public void open(Map<String, String> properties) {
    if (properties == null || properties.isEmpty()) {
      return;
    }
    Configurations configs = Configurations.fromMap(properties);
    register(
        configs, PARTIAL_COMPACT_ENABLED, PARTIAL_COMPACT_INTERVAL, PaimonActions.PARTIAL_COMPACT);
    register(configs, FULL_COMPACT_ENABLED, FULL_COMPACT_INTERVAL, PaimonActions.FULL_COMPACT);
    register(configs, ORPHAN_CLEAN_ENABLED, ORPHAN_CLEAN_INTERVAL, PaimonActions.CLEAN_ORPHAN);
    register(
        configs,
        EXPIRE_SNAPSHOTS_ENABLED,
        EXPIRE_SNAPSHOTS_INTERVAL,
        PaimonActions.EXPIRE_SNAPSHOTS);
    register(
        configs,
        EXPIRE_PARTITIONS_ENABLED,
        EXPIRE_PARTITIONS_INTERVAL,
        PaimonActions.EXPIRE_PARTITIONS);
  }

  @Override
  public void close() {}

  @Override
  public String name() {
    return PLUGIN_NAME;
  }

  private void register(
      Configurations configs,
      ConfigOption<Boolean> enabled,
      ConfigOption<Duration> interval,
      Action action) {
    if (configs.getBoolean(enabled)) {
      actions.put(action, ProcessTriggerStrategy.triggerAtFixRate(configs.getDuration(interval)));
    }
  }

  private TableProcess newProcess(TableRuntime tableRuntime, Action action) {
    if (PaimonActions.PARTIAL_COMPACT.equals(action)) {
      return new PaimonCompactProcess(tableRuntime, localEngine, false);
    }
    if (PaimonActions.FULL_COMPACT.equals(action)) {
      return new PaimonCompactProcess(tableRuntime, localEngine, true);
    }
    if (PaimonActions.CLEAN_ORPHAN.equals(action)) {
      return new PaimonOrphanFilesCleaningProcess(tableRuntime, localEngine);
    }
    if (PaimonActions.EXPIRE_SNAPSHOTS.equals(action)) {
      return new PaimonSnapshotsExpiringProcess(tableRuntime, localEngine);
    }
    if (PaimonActions.EXPIRE_PARTITIONS.equals(action)) {
      return new PaimonPartitionsExpiringProcess(tableRuntime, localEngine);
    }
    throw new IllegalArgumentException("Unsupported Paimon action: " + action);
  }

  /**
   * A pending manual request skips the interval. A disabled action drops that request so it cannot
   * run after the table property is turned back on.
   */
  private boolean readyToRun(TableRuntime tableRuntime, Action action) {
    if (localEngine == null || !actions.containsKey(action)) {
      return false;
    }
    PaimonMaintenanceConfig config =
        tableRuntime.getTableConfiguration().getPaimonMaintenanceConfig();
    PaimonMaintenanceState state = PaimonMaintenance.state(tableRuntime);
    boolean manual = state.hasManualAction(action.getName());
    if (!config.enabled(action)) {
      if (manual) {
        PaimonMaintenance.clearManual(tableRuntime, action);
      }
      return false;
    }
    if (manual) {
      return true;
    }
    Long override = config.intervalOverrideMillis(action);
    long interval =
        override == null ? actions.get(action).getTriggerInterval().toMillis() : override;
    return System.currentTimeMillis() - state.lastRunAt(action.getName()) >= interval;
  }
}
