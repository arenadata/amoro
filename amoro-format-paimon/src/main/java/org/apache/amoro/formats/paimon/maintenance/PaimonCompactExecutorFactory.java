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

import org.apache.amoro.paimon.PaimonCompactExecutor;
import org.apache.amoro.paimon.PaimonCompactInput;
import org.apache.paimon.CoreOptions;
import org.apache.paimon.append.AppendCompactTask;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.CatalogLoader;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.data.BinaryRow;
import org.apache.paimon.disk.IOManager;
import org.apache.paimon.operation.BaseAppendFileStoreWrite;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.sink.AppendCompactTaskSerializer;
import org.apache.paimon.table.sink.BatchTableWrite;
import org.apache.paimon.table.sink.CommitMessage;
import org.apache.paimon.utils.SerializationUtils;

import java.io.File;
import java.util.Collections;
import java.util.List;

/** Optimizer-side executor for one Paimon compact unit. */
public class PaimonCompactExecutorFactory
    implements org.apache.amoro.paimon.PaimonCompactExecutorFactory {

  @Override
  public PaimonCompactExecutor create(PaimonCompactInput input) {
    return new Executor();
  }

  static final class Executor implements PaimonCompactExecutor {

    @Override
    public byte[] execute(PaimonCompactInput input) throws Exception {
      CatalogLoader loader = PaimonCommitMessages.deserializeLoader(input.getCatalogLoaderBytes());
      try (Catalog catalog = loader.load()) {
        FileStoreTable table = open(catalog, input);
        table = table.copy(Collections.singletonMap(CoreOptions.WRITE_ONLY.key(), "false"));
        if (input.getKind() == PaimonCompactInput.Kind.BUCKET) {
          return compactBucket(table, input);
        }
        return compactAppend(table, input);
      }
    }

    private static FileStoreTable open(Catalog catalog, PaimonCompactInput input) throws Exception {
      String branch = input.getBranch();
      Identifier identifier =
          branch == null || branch.isEmpty()
              ? Identifier.create(input.getDatabase(), input.getTableName())
              : new Identifier(input.getDatabase(), input.getTableName(), branch);
      return (FileStoreTable) catalog.getTable(identifier);
    }

    private static byte[] compactBucket(FileStoreTable table, PaimonCompactInput input)
        throws Exception {
      BinaryRow partition = SerializationUtils.deserializeBinaryRow(input.getPartitionBytes());
      File tempDir = File.createTempFile("paimon-compact-", "");
      if (!tempDir.delete() || !tempDir.mkdirs()) {
        throw new IllegalStateException("Failed to create temp dir for Paimon compaction");
      }
      IOManager ioManager = IOManager.create(tempDir.getAbsolutePath());
      BatchTableWrite write = table.newBatchWriteBuilder().newWrite();
      write.withIOManager(ioManager);
      try {
        write.compact(partition, input.getBucket(), input.isFullCompact());
        List<CommitMessage> messages = write.prepareCommit();
        return PaimonCommitMessages.serialize(messages);
      } finally {
        write.close();
        ioManager.close();
        deleteQuietly(tempDir);
      }
    }

    private static byte[] compactAppend(FileStoreTable table, PaimonCompactInput input)
        throws Exception {
      AppendCompactTaskSerializer serializer = new AppendCompactTaskSerializer();
      AppendCompactTask task =
          serializer.deserialize(serializer.getVersion(), input.getAppendTaskBytes());
      BaseAppendFileStoreWrite write =
          (BaseAppendFileStoreWrite) table.store().newWrite(input.getCommitUser());
      try {
        CommitMessage message = task.doCompact(table, write);
        if (message == null) {
          return new byte[0];
        }
        return PaimonCommitMessages.serialize(Collections.singletonList(message));
      } finally {
        write.close();
      }
    }

    private static void deleteQuietly(File file) {
      File[] children = file.listFiles();
      if (children != null) {
        for (File child : children) {
          deleteQuietly(child);
        }
      }
      file.delete();
    }
  }
}
