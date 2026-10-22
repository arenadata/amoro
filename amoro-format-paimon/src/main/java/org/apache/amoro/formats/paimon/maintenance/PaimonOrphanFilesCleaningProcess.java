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
import org.apache.paimon.operation.CleanOrphanFilesResult;
import org.apache.paimon.operation.LocalOrphanFilesClean;
import org.apache.paimon.table.FileStoreTable;

import java.util.Map;

/** Removes Paimon orphan files older than the configured minimum age. */
public class PaimonOrphanFilesCleaningProcess extends PaimonLocalMaintenanceProcess {

  public PaimonOrphanFilesCleaningProcess(TableRuntime tableRuntime, ExecuteEngine engine) {
    super(tableRuntime, engine);
  }

  @Override
  protected Map<String, String> execute(FileStoreTable table) throws Exception {
    long olderThan =
        System.currentTimeMillis()
            - tableRuntime
                .getTableConfiguration()
                .getPaimonMaintenanceConfig()
                .getOrphanMinExistingMillis();
    CleanOrphanFilesResult result = new LocalOrphanFilesClean(table, olderThan).clean();
    Map<String, String> summary = Maps.newLinkedHashMap();
    summary.put("deleted-file-count", String.valueOf(result.getDeletedFileCount()));
    summary.put("deleted-file-bytes", String.valueOf(result.getDeletedFileTotalLenInBytes()));
    return summary;
  }

  @Override
  public Action getAction() {
    return PaimonActions.CLEAN_ORPHAN;
  }
}
