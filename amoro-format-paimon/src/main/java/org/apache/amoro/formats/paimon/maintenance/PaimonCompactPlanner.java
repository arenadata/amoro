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

import org.apache.amoro.formats.paimon.maintenance.PaimonCompactPlan.PlannedTask;
import org.apache.amoro.paimon.PaimonCompactInput;
import org.apache.paimon.CoreOptions;
import org.apache.paimon.append.AppendCompactCoordinator;
import org.apache.paimon.append.AppendCompactTask;
import org.apache.paimon.catalog.CatalogLoader;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.data.BinaryRow;
import org.apache.paimon.io.DataFileMeta;
import org.apache.paimon.manifest.BucketEntry;
import org.apache.paimon.manifest.FileKind;
import org.apache.paimon.manifest.ManifestEntry;
import org.apache.paimon.table.BucketMode;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.sink.AppendCompactTaskSerializer;
import org.apache.paimon.table.source.EndOfScanException;
import org.apache.paimon.types.DataField;
import org.apache.paimon.utils.InternalRowUtils;
import org.apache.paimon.utils.SerializationUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Plans partial (Paimon minor) and full compaction units. File selection uses native options. */
public final class PaimonCompactPlanner {

  private PaimonCompactPlanner() {}

  static PaimonCompactPlan rejectUnsupported(BucketMode mode) {
    if (mode == BucketMode.HASH_FIXED || mode == BucketMode.BUCKET_UNAWARE) {
      return null;
    }
    return PaimonCompactPlan.unsupported(
        "Paimon compaction does not support bucket mode "
            + mode
            + ". Supported modes are HASH_FIXED and BUCKET_UNAWARE.");
  }

  public static PaimonCompactPlan plan(FileStoreTable table, boolean fullCompact) {
    BucketMode mode = table.bucketMode();
    PaimonCompactPlan rejected = rejectUnsupported(mode);
    if (rejected != null) {
      return rejected;
    }
    CatalogLoader loader = table.catalogEnvironment().catalogLoader();
    if (loader == null) {
      return PaimonCompactPlan.unsupported(
          "Paimon table has no catalog loader, so compaction cannot run on an optimizer");
    }
    FileStoreTable writable =
        table.copy(Collections.singletonMap(CoreOptions.WRITE_ONLY.key(), "false"));
    byte[] loaderBytes = PaimonCommitMessages.serializeLoader(loader);
    String commitUser = writable.coreOptions().createCommitUser();
    Identifier identifier = writable.catalogEnvironment().identifier();
    if (mode == BucketMode.HASH_FIXED) {
      return PaimonCompactPlan.of(
          planBuckets(writable, fullCompact, loaderBytes, commitUser, identifier));
    }
    return PaimonCompactPlan.of(
        planAppend(writable, fullCompact, loaderBytes, commitUser, identifier));
  }

  private static List<PlannedTask> planBuckets(
      FileStoreTable table,
      boolean fullCompact,
      byte[] loaderBytes,
      String commitUser,
      Identifier identifier) {
    List<PlannedTask> tasks = new ArrayList<>();
    Map<String, Boolean> seen = new LinkedHashMap<>();
    for (BucketEntry entry : table.newSnapshotReader().bucketEntries()) {
      if (entry.fileCount() <= 0) {
        continue;
      }
      String key = entry.partition() + "#" + entry.bucket();
      if (seen.putIfAbsent(key, Boolean.TRUE) != null) {
        continue;
      }
      PaimonCompactInput input =
          baseInput(
                  identifier, loaderBytes, commitUser, fullCompact, PaimonCompactInput.Kind.BUCKET)
              .setPartitionBytes(SerializationUtils.serializeBinaryRow(entry.partition()))
              .setBucket(entry.bucket());
      tasks.add(new PlannedTask(label(table, entry.partition(), entry.bucket()), input));
    }
    return tasks;
  }

  private static List<PlannedTask> planAppend(
      FileStoreTable table,
      boolean fullCompact,
      byte[] loaderBytes,
      String commitUser,
      Identifier identifier) {
    List<AppendCompactTask> compactTasks =
        fullCompact ? fullAppendTasks(table) : partialAppendTasks(table);
    AppendCompactTaskSerializer serializer = new AppendCompactTaskSerializer();
    List<PlannedTask> tasks = new ArrayList<>();
    for (AppendCompactTask compactTask : compactTasks) {
      byte[] bytes;
      try {
        bytes = serializer.serialize(compactTask);
      } catch (IOException e) {
        throw new IllegalStateException("Failed to serialize Paimon append compact task", e);
      }
      PaimonCompactInput input =
          baseInput(
                  identifier, loaderBytes, commitUser, fullCompact, PaimonCompactInput.Kind.APPEND)
              .setAppendTaskBytes(bytes)
              .setPartitionBytes(SerializationUtils.serializeBinaryRow(compactTask.partition()));
      tasks.add(new PlannedTask(label(table, compactTask.partition(), -1), input));
    }
    return tasks;
  }

  private static List<AppendCompactTask> partialAppendTasks(FileStoreTable table) {
    AppendCompactCoordinator coordinator = new AppendCompactCoordinator(table, false);
    List<AppendCompactTask> tasks = new ArrayList<>();
    try {
      while (true) {
        List<AppendCompactTask> batch = coordinator.run();
        if (batch.isEmpty()) {
          break;
        }
        tasks.addAll(batch);
      }
    } catch (EndOfScanException ignored) {
      // The coordinator signals the end of a batch scan this way.
    }
    return tasks;
  }

  private static List<AppendCompactTask> fullAppendTasks(FileStoreTable table) {
    Map<BinaryRow, List<DataFileMeta>> filesByPartition = new HashMap<>();
    Iterator<ManifestEntry> iterator = table.newSnapshotReader().readFileIterator();
    while (iterator.hasNext()) {
      ManifestEntry entry = iterator.next();
      if (entry.kind() != FileKind.ADD) {
        continue;
      }
      filesByPartition
          .computeIfAbsent(entry.partition(), key -> new ArrayList<>())
          .add(entry.file());
    }
    List<AppendCompactTask> tasks = new ArrayList<>();
    for (Map.Entry<BinaryRow, List<DataFileMeta>> entry : filesByPartition.entrySet()) {
      if (entry.getValue().size() > 1) {
        tasks.add(new AppendCompactTask(entry.getKey(), entry.getValue()));
      }
    }
    return tasks;
  }

  private static PaimonCompactInput baseInput(
      Identifier identifier,
      byte[] loaderBytes,
      String commitUser,
      boolean fullCompact,
      PaimonCompactInput.Kind kind) {
    return new PaimonCompactInput()
        .setDatabase(identifier.getDatabaseName())
        .setTableName(identifier.getTableName())
        .setBranch(identifier.getBranchName())
        .setCatalogLoaderBytes(loaderBytes)
        .setCommitUser(commitUser)
        .setFullCompact(fullCompact)
        .setKind(kind);
  }

  private static String label(FileStoreTable table, BinaryRow partition, int bucket) {
    StringBuilder builder = new StringBuilder();
    List<DataField> fields = table.schema().logicalPartitionType().getFields();
    for (int i = 0; i < partition.getFieldCount() && i < fields.size(); i++) {
      if (builder.length() > 0) {
        builder.append('/');
      }
      Object value =
          partition.isNullAt(i) ? "null" : InternalRowUtils.get(partition, i, fields.get(i).type());
      builder.append(fields.get(i).name()).append('=').append(value);
    }
    if (bucket >= 0) {
      if (builder.length() > 0) {
        builder.append('/');
      }
      builder.append("bucket-").append(bucket);
    }
    if (builder.length() == 0) {
      builder.append(bucket >= 0 ? "bucket-" + bucket : "append");
    }
    String text = builder.toString();
    return text.length() <= 120 ? text : text.substring(0, 120);
  }
}
