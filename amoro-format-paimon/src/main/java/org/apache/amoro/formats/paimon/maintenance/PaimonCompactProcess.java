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
import org.apache.amoro.TableRuntime;
import org.apache.amoro.config.OptimizingConfig;
import org.apache.amoro.formats.paimon.maintenance.PaimonCompactPlan.PlannedTask;
import org.apache.amoro.paimon.PaimonCompactSubmission;
import org.apache.amoro.paimon.PaimonCompactSubmitter;
import org.apache.amoro.paimon.PaimonCompactSubmitters;
import org.apache.amoro.paimon.PaimonCompactTaskSpec;
import org.apache.amoro.process.ExecuteEngine;
import org.apache.amoro.process.ProcessIdentityAware;
import org.apache.amoro.shade.guava32.com.google.common.collect.Maps;
import org.apache.amoro.utils.SerializationUtil;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.sink.BatchTableCommit;
import org.apache.paimon.table.sink.CommitMessage;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/** Plans a Paimon compaction in AMS, runs the units on Spark optimizers, then commits. */
public class PaimonCompactProcess extends PaimonLocalMaintenanceProcess
    implements ProcessIdentityAware {

  private final boolean fullCompact;
  private volatile long processId;

  public PaimonCompactProcess(
      TableRuntime tableRuntime, ExecuteEngine engine, boolean fullCompact) {
    super(tableRuntime, engine);
    this.fullCompact = fullCompact;
  }

  @Override
  public void bindProcessId(long processId) {
    this.processId = processId;
  }

  @Override
  protected Map<String, String> execute(FileStoreTable table) throws Exception {
    if (processId <= 0) {
      throw new IllegalStateException("Paimon compaction process id has not been bound");
    }
    PaimonCompactPlan plan = PaimonCompactPlanner.plan(table, fullCompact);
    Map<String, String> summary = Maps.newLinkedHashMap();
    summary.put("compact-strategy", fullCompact ? "full" : "partial");
    if (plan.unsupported()) {
      throw new IllegalStateException(plan.unsupportedReason());
    }
    if (plan.tasks().isEmpty()) {
      summary.put("compacted", "false");
      summary.put("tasks", "0");
      return summary;
    }
    PaimonCompactSubmitter submitter = PaimonCompactSubmitters.get();
    if (submitter == null) {
      throw new IllegalStateException("Paimon compaction queue is not installed on this AMS");
    }
    List<PaimonCompactTaskSpec> specs = new ArrayList<>();
    int taskId = 1;
    for (PlannedTask task : plan.tasks()) {
      ByteBuffer serialized = SerializationUtil.simpleSerialize(task.input());
      byte[] bytes = new byte[serialized.remaining()];
      serialized.get(bytes);
      specs.add(new PaimonCompactTaskSpec(taskId++, task.partition(), bytes));
    }
    OptimizingConfig optimizingConfig = tableRuntime.getTableConfiguration().getOptimizingConfig();
    String group =
        optimizingConfig == null || optimizingConfig.getOptimizerGroup() == null
            ? "default"
            : optimizingConfig.getOptimizerGroup();
    double quota = optimizingConfig == null ? 0.5D : optimizingConfig.getTargetQuota();
    List<byte[]> outputs;
    try {
      outputs =
          submitter.submitAndAwait(
              new PaimonCompactSubmission(
                  processId, tableRuntime.getTableIdentifier().getId(), group, quota, specs));
    } catch (CancellationException e) {
      summary.put("compacted", "false");
      summary.put("canceled", "true");
      return summary;
    }
    List<CommitMessage> messages = new ArrayList<>();
    for (byte[] output : outputs) {
      messages.addAll(PaimonCommitMessages.deserialize(output));
    }
    if (!messages.isEmpty()) {
      try (BatchTableCommit commit = table.newBatchWriteBuilder().newCommit()) {
        commit.commit(messages);
      }
    }
    summary.put("compacted", "true");
    summary.put("tasks", String.valueOf(specs.size()));
    summary.put("commit-messages", String.valueOf(messages.size()));
    return summary;
  }

  @Override
  public Action getAction() {
    return fullCompact ? PaimonActions.FULL_COMPACT : PaimonActions.PARTIAL_COMPACT;
  }
}
