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

import org.apache.amoro.PaimonActions;
import org.apache.amoro.TableRuntime;
import org.apache.amoro.config.PaimonMaintenanceConfig;
import org.apache.amoro.config.TableConfiguration;
import org.apache.amoro.paimon.PaimonCompactExecutor;
import org.apache.amoro.process.LocalExecutionEngine;
import org.apache.amoro.process.TableProcess;
import org.apache.amoro.table.PaimonMaintenance;
import org.apache.amoro.table.PaimonMaintenanceState;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.catalog.CatalogFactory;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.data.BinaryString;
import org.apache.paimon.data.GenericRow;
import org.apache.paimon.options.CatalogOptions;
import org.apache.paimon.options.Options;
import org.apache.paimon.schema.Schema;
import org.apache.paimon.table.BucketMode;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.sink.BatchTableCommit;
import org.apache.paimon.table.sink.BatchTableWrite;
import org.apache.paimon.table.sink.CommitMessage;
import org.apache.paimon.types.DataTypes;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class TestPaimonMaintenance {

  @TempDir java.nio.file.Path warehouse;

  @Test
  public void plansFixedBucketAndAppendCompactionAndRejectsPostpone() throws Exception {
    try (Catalog catalog = catalog()) {
      catalog.createDatabase("db", true);
      FileStoreTable fixed = createTable(catalog, "fixed", true, "2");
      commitRow(fixed, 1, "a");
      PaimonCompactPlan fixedPlan = PaimonCompactPlanner.plan(fixed, false);
      Assertions.assertFalse(fixedPlan.unsupported());
      Assertions.assertFalse(fixedPlan.tasks().isEmpty());

      PaimonCompactExecutor executor = new PaimonCompactExecutorFactory().create(null);
      byte[] output = executor.execute(fixedPlan.tasks().get(0).input());
      List<CommitMessage> messages = PaimonCommitMessages.deserialize(output);
      if (!messages.isEmpty()) {
        try (BatchTableCommit commit = fixed.newBatchWriteBuilder().newCommit()) {
          commit.commit(messages);
        }
      }

      FileStoreTable append = createTable(catalog, "append", false, "-1");
      commitRow(append, 1, "a");
      commitRow(append, 2, "b");
      PaimonCompactPlan fullAppend = PaimonCompactPlanner.plan(append, true);
      Assertions.assertFalse(fullAppend.unsupported());
      Assertions.assertFalse(fullAppend.tasks().isEmpty());

      PaimonCompactPlan rejected = PaimonCompactPlanner.rejectUnsupported(BucketMode.POSTPONE_MODE);
      Assertions.assertNotNull(rejected);
      Assertions.assertTrue(rejected.unsupported());
      Assertions.assertTrue(rejected.unsupportedReason().contains("does not support"));
      Assertions.assertNull(PaimonCompactPlanner.rejectUnsupported(BucketMode.HASH_FIXED));
    }
  }

  @Test
  public void expiresSnapshotsCleansOrphansAndSkipsUnsetPartitionExpiration() throws Exception {
    try (Catalog catalog = catalog()) {
      catalog.createDatabase("db", true);
      Identifier identifier = Identifier.create("db", "gc");
      catalog.createTable(identifier, snapshotSchema(), false);
      FileStoreTable table = (FileStoreTable) catalog.getTable(identifier);
      commitRow(table, 1, "a");
      commitRow(table, 2, "b");
      commitRow(table, 3, "c");
      int expired = table.newExpireSnapshots().expire();
      Assertions.assertTrue(expired >= 0);

      org.apache.paimon.fs.Path orphan =
          new org.apache.paimon.fs.Path(
              new org.apache.paimon.fs.Path(table.location(), "bucket-0"), "orphan-test-file");
      try (java.io.OutputStream out = table.fileIO().newOutputStream(orphan, false)) {
        out.write(new byte[] {1, 2, 3});
      }
      long olderThan = System.currentTimeMillis() + 120_000L;
      org.apache.paimon.operation.CleanOrphanFilesResult cleaned =
          new org.apache.paimon.operation.LocalOrphanFilesClean(table, olderThan).clean();
      Assertions.assertTrue(
          cleaned.getDeletedFileCount() >= 1, "orphan file in bucket-0 should be removed");

      Map<String, String> summary =
          new PaimonPartitionsExpiringProcess(runtime(), null).execute(table);
      Assertions.assertEquals("true", summary.get("skipped"));
    }
  }

  @Test
  public void schedulerHonorsEnableFlagIntervalAndManualRequest() {
    PaimonProcessFactory factory = new PaimonProcessFactory();
    Map<String, String> plugin = new HashMap<>();
    plugin.put("partial-compact.enabled", "true");
    plugin.put("partial-compact.interval", "1h");
    plugin.put("expire-snapshots-paimon.enabled", "true");
    plugin.put("expire-snapshots-paimon.interval", "1h");
    factory.open(plugin);
    LocalExecutionEngine engine = new LocalExecutionEngine();
    engine.open(Collections.singletonMap("pool.default.thread-count", "1"));
    factory.availableExecuteEngines(Collections.singletonList(engine));

    Map<String, String> tableProperties = new HashMap<>();
    tableProperties.put("maintenance.partial-compact.enabled", "true");
    tableProperties.put("maintenance.partial-compact.interval", "1h");
    tableProperties.put("maintenance.expire-snapshots.enabled", "false");
    TableRuntime runtime =
        runtime(PaimonMaintenanceConfig.parse(tableProperties), new PaimonMaintenanceState());
    Optional<TableProcess> compact = factory.trigger(runtime, PaimonActions.PARTIAL_COMPACT);
    Assertions.assertTrue(compact.isPresent());
    Optional<TableProcess> expire = factory.trigger(runtime, PaimonActions.EXPIRE_SNAPSHOTS);
    Assertions.assertFalse(expire.isPresent());

    PaimonMaintenanceState recent = new PaimonMaintenanceState();
    recent.stamp(PaimonActions.PARTIAL_COMPACT.getName(), System.currentTimeMillis());
    TableRuntime throttled = runtime(PaimonMaintenanceConfig.parse(tableProperties), recent);
    Assertions.assertFalse(factory.trigger(throttled, PaimonActions.PARTIAL_COMPACT).isPresent());

    PaimonMaintenanceState manual = new PaimonMaintenanceState();
    manual.stamp(PaimonActions.PARTIAL_COMPACT.getName(), System.currentTimeMillis());
    manual.addManualAction(PaimonActions.PARTIAL_COMPACT.getName(), System.currentTimeMillis());
    TableRuntime manualRuntime = runtime(PaimonMaintenanceConfig.parse(tableProperties), manual);
    Assertions.assertTrue(
        factory.trigger(manualRuntime, PaimonActions.PARTIAL_COMPACT).isPresent());
    engine.close();
  }

  private Catalog catalog() {
    Options options = new Options();
    options.set(CatalogOptions.WAREHOUSE, warehouse.toString());
    return CatalogFactory.createCatalog(CatalogContext.create(options));
  }

  private FileStoreTable createTable(
      Catalog catalog, String name, boolean primaryKey, String bucket) throws Exception {
    Schema.Builder builder =
        Schema.newBuilder().column("id", DataTypes.INT()).column("name", DataTypes.STRING());
    if (primaryKey) {
      builder.primaryKey("id");
      builder.option("bucket-key", "id");
    }
    builder.option("bucket", bucket);
    builder.option("compaction.min.file-num", "2");
    Identifier identifier = Identifier.create("db", name);
    catalog.createTable(identifier, builder.build(), false);
    return (FileStoreTable) catalog.getTable(identifier);
  }

  private Schema snapshotSchema() {
    return Schema.newBuilder()
        .column("id", DataTypes.INT())
        .column("name", DataTypes.STRING())
        .option("bucket", "-1")
        .option("snapshot.num-retained.min", "1")
        .option("snapshot.num-retained.max", "1")
        .build();
  }

  private void commitRow(FileStoreTable table, int id, String name) throws Exception {
    try (BatchTableWrite write = table.newBatchWriteBuilder().newWrite()) {
      write.write(GenericRow.of(id, BinaryString.fromString(name)));
      List<CommitMessage> messages = write.prepareCommit();
      try (BatchTableCommit commit = table.newBatchWriteBuilder().newCommit()) {
        commit.commit(messages);
      }
    }
  }

  private TableRuntime runtime() {
    return runtime(PaimonMaintenanceConfig.defaults(), new PaimonMaintenanceState());
  }

  private TableRuntime runtime(PaimonMaintenanceConfig config, PaimonMaintenanceState state) {
    return new TableRuntime() {
      @Override
      public java.util.List<? extends org.apache.amoro.process.TableProcessStore>
          getProcessStates() {
        throw new UnsupportedOperationException();
      }

      @Override
      public java.util.List<? extends org.apache.amoro.process.TableProcessStore> getProcessStates(
          org.apache.amoro.Action action) {
        throw new UnsupportedOperationException();
      }

      @Override
      public String getGroupName() {
        return "default";
      }

      @Override
      public org.apache.amoro.ServerTableIdentifier getTableIdentifier() {
        throw new UnsupportedOperationException();
      }

      @Override
      public org.apache.amoro.AmoroTable<?> loadTable() {
        throw new UnsupportedOperationException();
      }

      @Override
      public TableConfiguration getTableConfiguration() {
        return new TableConfiguration().setPaimonMaintenanceConfig(config);
      }

      @Override
      public java.util.Map<String, String> getTableConfig() {
        return java.util.Collections.emptyMap();
      }

      @Override
      @SuppressWarnings("unchecked")
      public <T> T getState(org.apache.amoro.table.StateKey<T> key) {
        if (PaimonMaintenance.STATE_KEY.equals(key)) {
          return (T) state;
        }
        return null;
      }

      @Override
      public <T> void updateState(
          org.apache.amoro.table.StateKey<T> key, java.util.function.Function<T, T> updater) {}

      @Override
      public void registerMetric(org.apache.amoro.metrics.MetricRegistry metricRegistry) {}

      @Override
      public void unregisterMetric() {}
    };
  }
}
