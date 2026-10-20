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

import java.io.Serializable;

/** One planned compact task, ready to be offered to an optimizer. */
public class PaimonCompactTaskSpec implements Serializable {

  private final int taskId;
  private final String partition;
  private final byte[] taskInput;

  public PaimonCompactTaskSpec(int taskId, String partition, byte[] taskInput) {
    this.taskId = taskId;
    this.partition = partition;
    this.taskInput = taskInput;
  }

  public int getTaskId() {
    return taskId;
  }

  public String getPartition() {
    return partition;
  }

  public byte[] getTaskInput() {
    return taskInput;
  }
}
