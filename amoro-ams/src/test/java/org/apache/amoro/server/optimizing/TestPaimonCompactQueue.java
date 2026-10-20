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

package org.apache.amoro.server.optimizing;

import org.apache.amoro.api.OptimizingTask;
import org.apache.amoro.api.OptimizingTaskResult;
import org.apache.amoro.paimon.PaimonCompactSubmission;
import org.apache.amoro.paimon.PaimonCompactTaskSpec;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class TestPaimonCompactQueue {

  @Test
  public void pollAckAndCompleteReturnsCommitBytes() throws Exception {
    PaimonCompactQueue queue = new PaimonCompactQueue(PaimonCompactQueue.TaskStore.NOOP);
    byte[] input = new byte[] {1, 2, 3};
    PaimonCompactSubmission submission =
        new PaimonCompactSubmission(
            11L,
            5L,
            "default",
            1.0D,
            Collections.singletonList(new PaimonCompactTaskSpec(1, "bucket-0", input)));
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<List<byte[]>> awaiting = executor.submit(() -> queue.submitAndAwait(submission));
      OptimizingTask task = null;
      for (int i = 0; i < 50 && task == null; i++) {
        task = queue.poll("default", "optimizer-token", 0, 2);
        if (task == null) {
          Thread.sleep(20L);
        }
      }
      Assertions.assertNotNull(task);
      Assertions.assertArrayEquals(input, task.getTaskInput());
      queue.ack(task.getTaskId(), "optimizer-token", 0);
      OptimizingTaskResult result = new OptimizingTaskResult(task.getTaskId(), 0);
      result.setTaskOutput(new byte[] {9, 8});
      Assertions.assertTrue(queue.complete(result));
      List<byte[]> outputs = awaiting.get(5, TimeUnit.SECONDS);
      Assertions.assertEquals(1, outputs.size());
      Assertions.assertArrayEquals(new byte[] {9, 8}, outputs.get(0));
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  public void failedTaskFailsTheSubmission() throws Exception {
    PaimonCompactQueue queue = new PaimonCompactQueue(PaimonCompactQueue.TaskStore.NOOP);
    PaimonCompactSubmission submission =
        new PaimonCompactSubmission(
            12L,
            5L,
            "default",
            1.0D,
            Collections.singletonList(new PaimonCompactTaskSpec(3, "bucket-1", new byte[] {1})));
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<List<byte[]>> awaiting = executor.submit(() -> queue.submitAndAwait(submission));
      OptimizingTask task = null;
      for (int i = 0; i < 50 && task == null; i++) {
        task = queue.poll("default", "optimizer-token", 1, 1);
        if (task == null) {
          Thread.sleep(20L);
        }
      }
      Assertions.assertNotNull(task);
      queue.ack(task.getTaskId(), "optimizer-token", 1);
      OptimizingTaskResult result = new OptimizingTaskResult(task.getTaskId(), 1);
      result.setErrorMessage("compact failed");
      queue.complete(result);
      Exception error =
          Assertions.assertThrows(Exception.class, () -> awaiting.get(5, TimeUnit.SECONDS));
      Assertions.assertTrue(error.getMessage().contains("compact failed"));
    } finally {
      executor.shutdownNow();
    }
  }
}
