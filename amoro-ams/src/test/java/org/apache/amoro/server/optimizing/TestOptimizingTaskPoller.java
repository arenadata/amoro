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
import org.apache.amoro.api.OptimizingTaskId;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class TestOptimizingTaskPoller {

  @Test
  public void paimonTaskIsVisibleWhileIcebergQueueIsWaiting() {
    AtomicInteger icebergCalls = new AtomicInteger();
    OptimizingTask paimonTask = new OptimizingTask(new OptimizingTaskId(42L, 1));
    OptimizingTask polled =
        OptimizingTaskPoller.poll(
            new OptimizingTaskPoller.Sources() {
              @Override
              public OptimizingTask pollIceberg(long waitMs) {
                int call = icebergCalls.incrementAndGet();
                if (waitMs > 0 && call < 4) {
                  try {
                    Thread.sleep(Math.min(waitMs, 15L));
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                }
                return null;
              }

              @Override
              public OptimizingTask pollPaimon() {
                return icebergCalls.get() >= 3 ? paimonTask : null;
              }
            },
            5_000L,
            20L,
            true);
    Assertions.assertSame(paimonTask, polled);
  }

  @Test
  public void icebergTaskIsReturnedWhenBothAreReady() {
    OptimizingTask icebergTask = new OptimizingTask(new OptimizingTaskId(1L, 1));
    OptimizingTask paimonTask = new OptimizingTask(new OptimizingTaskId(2L, 1));
    OptimizingTask polled =
        OptimizingTaskPoller.poll(
            new OptimizingTaskPoller.Sources() {
              @Override
              public OptimizingTask pollIceberg(long waitMs) {
                return icebergTask;
              }

              @Override
              public OptimizingTask pollPaimon() {
                return paimonTask;
              }
            },
            1_000L,
            50L,
            true);
    Assertions.assertSame(icebergTask, polled);
  }

  @Test
  public void disabledPaimonUsesASingleIcebergWait() {
    AtomicLong waited = new AtomicLong(-1L);
    OptimizingTask icebergTask = new OptimizingTask(new OptimizingTaskId(3L, 1));
    OptimizingTask polled =
        OptimizingTaskPoller.poll(
            new OptimizingTaskPoller.Sources() {
              @Override
              public OptimizingTask pollIceberg(long waitMs) {
                waited.set(waitMs);
                return icebergTask;
              }

              @Override
              public OptimizingTask pollPaimon() {
                throw new AssertionError("paimon poll should be skipped");
              }
            },
            4_000L,
            200L,
            false);
    Assertions.assertSame(icebergTask, polled);
    Assertions.assertEquals(4_000L, waited.get());
  }
}
