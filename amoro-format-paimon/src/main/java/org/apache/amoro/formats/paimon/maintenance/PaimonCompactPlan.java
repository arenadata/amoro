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

import org.apache.amoro.paimon.PaimonCompactInput;

import java.util.Collections;
import java.util.List;

/** Result of planning one partial or full compaction. */
public final class PaimonCompactPlan {

  private final List<PlannedTask> tasks;
  private final String unsupportedReason;

  private PaimonCompactPlan(List<PlannedTask> tasks, String unsupportedReason) {
    this.tasks = tasks;
    this.unsupportedReason = unsupportedReason;
  }

  public static PaimonCompactPlan of(List<PlannedTask> tasks) {
    return new PaimonCompactPlan(tasks, null);
  }

  public static PaimonCompactPlan unsupported(String reason) {
    return new PaimonCompactPlan(Collections.emptyList(), reason);
  }

  public boolean unsupported() {
    return unsupportedReason != null;
  }

  public String unsupportedReason() {
    return unsupportedReason;
  }

  public List<PlannedTask> tasks() {
    return tasks;
  }

  /** A compact unit plus the partition label stored on the task row. */
  public static final class PlannedTask {
    private final String partition;
    private final PaimonCompactInput input;

    public PlannedTask(String partition, PaimonCompactInput input) {
      this.partition = partition;
      this.input = input;
    }

    public String partition() {
      return partition;
    }

    public PaimonCompactInput input() {
      return input;
    }
  }
}
