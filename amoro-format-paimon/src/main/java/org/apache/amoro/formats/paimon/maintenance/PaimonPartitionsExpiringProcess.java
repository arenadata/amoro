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
import org.apache.amoro.process.ExecuteEngine;
import org.apache.amoro.shade.guava32.com.google.common.collect.Maps;
import org.apache.paimon.CoreOptions;
import org.apache.paimon.operation.PartitionExpire;
import org.apache.paimon.table.FileStoreTable;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Expires Paimon partitions using the native {@code partition.expiration-time} option. */
public class PaimonPartitionsExpiringProcess extends PaimonLocalMaintenanceProcess {

  public PaimonPartitionsExpiringProcess(TableRuntime tableRuntime, ExecuteEngine engine) {
    super(tableRuntime, engine);
  }

  @Override
  protected Map<String, String> execute(FileStoreTable table) {
    Map<String, String> summary = Maps.newLinkedHashMap();
    Duration expiration = table.coreOptions().partitionExpireTime();
    if (expiration == null) {
      summary.put("skipped", "true");
      summary.put("reason", "partition.expiration-time is not set");
      return summary;
    }
    String commitUser = CoreOptions.createCommitUser(table.coreOptions().toConfiguration());
    PartitionExpire partitionExpire = table.store().newPartitionExpire(commitUser, table);
    List<Map<String, String>> expired = partitionExpire.expire(System.currentTimeMillis());
    summary.put("expired-partitions", String.valueOf(expired == null ? 0 : expired.size()));
    return summary;
  }

  @Override
  public Action getAction() {
    return PaimonActions.EXPIRE_PARTITIONS;
  }
}
