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

package org.apache.amoro.paimon;

import org.apache.amoro.api.OptimizingTask;
import org.apache.amoro.api.OptimizingTaskId;
import org.apache.amoro.api.OptimizingTaskResult;

import java.util.List;

/** AMS side of Paimon compaction. Installed by {@code DefaultOptimizingService}. */
public interface PaimonCompactSubmitter {

  List<byte[]> submitAndAwait(PaimonCompactSubmission submission) throws Exception;

  void cancel(long processId);

  /** Non-blocking poll for one optimizer thread. */
  OptimizingTask poll(String optimizerGroup, String optimizerToken, int threadId, int groupThreads);

  boolean ack(OptimizingTaskId taskId, String optimizerToken, int threadId);

  boolean complete(OptimizingTaskResult result);
}
