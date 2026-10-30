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

/**
 * Polls the Iceberg optimizing queue and the Paimon compact queue together. Iceberg's poll blocks
 * on its own condition, so a single full-timeout wait would hide Paimon tasks that arrive during
 * that wait.
 */
public final class OptimizingTaskPoller {

  static final long DEFAULT_SLICE_MS = 200L;

  private OptimizingTaskPoller() {}

  public interface Sources {
    /** Waits up to {@code waitMs}. Zero waits without blocking. */
    OptimizingTask pollIceberg(long waitMs);

    /** Non-blocking. Null when Paimon compaction is not installed or the queue is empty. */
    OptimizingTask pollPaimon();
  }

  public static OptimizingTask poll(Sources sources, long timeoutMs, boolean paimonEnabled) {
    return poll(sources, timeoutMs, DEFAULT_SLICE_MS, paimonEnabled);
  }

  public static OptimizingTask poll(
      Sources sources, long timeoutMs, long sliceMs, boolean paimonEnabled) {
    if (!paimonEnabled) {
      return sources.pollIceberg(timeoutMs);
    }
    long deadline = System.currentTimeMillis() + Math.max(timeoutMs, 0L);
    long slice = Math.max(sliceMs, 1L);
    while (true) {
      OptimizingTask iceberg = sources.pollIceberg(0L);
      if (iceberg != null) {
        return iceberg;
      }
      OptimizingTask paimon = sources.pollPaimon();
      if (paimon != null) {
        return paimon;
      }
      long remaining = deadline - System.currentTimeMillis();
      if (remaining <= 0) {
        return null;
      }
      iceberg = sources.pollIceberg(Math.min(slice, remaining));
      if (iceberg != null) {
        return iceberg;
      }
    }
  }
}
