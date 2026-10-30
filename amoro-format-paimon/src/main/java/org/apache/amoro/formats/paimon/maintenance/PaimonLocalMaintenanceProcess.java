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

import org.apache.amoro.AmoroTable;
import org.apache.amoro.TableRuntime;
import org.apache.amoro.process.ExecuteEngine;
import org.apache.amoro.process.LocalProcess;
import org.apache.amoro.process.TableProcess;
import org.apache.amoro.shade.guava32.com.google.common.collect.Maps;
import org.apache.amoro.table.PaimonMaintenance;
import org.apache.paimon.table.FileStoreTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;

/** Shared local-process boilerplate for Paimon maintenance. */
abstract class PaimonLocalMaintenanceProcess extends TableProcess implements LocalProcess {

  private static final Logger LOG = LoggerFactory.getLogger(PaimonLocalMaintenanceProcess.class);

  private volatile Map<String, String> summary = Maps.newLinkedHashMap();

  PaimonLocalMaintenanceProcess(TableRuntime tableRuntime, ExecuteEngine engine) {
    super(tableRuntime, engine);
  }

  @Override
  public String tag() {
    return getAction().getName().toLowerCase(Locale.ROOT);
  }

  @Override
  public void run() {
    PaimonMaintenance.clearManual(tableRuntime, getAction());
    try {
      summary = execute(fileStoreTable());
      if (summary == null) {
        summary = Maps.newLinkedHashMap();
      }
    } catch (RuntimeException e) {
      LOG.error(
          "Paimon maintenance {} failed for {}",
          getAction().getName(),
          tableRuntime.getTableIdentifier(),
          e);
      throw e;
    } catch (Exception e) {
      LOG.error(
          "Paimon maintenance {} failed for {}",
          getAction().getName(),
          tableRuntime.getTableIdentifier(),
          e);
      throw new RuntimeException(e);
    } finally {
      PaimonMaintenance.stamp(tableRuntime, getAction());
    }
  }

  protected abstract Map<String, String> execute(FileStoreTable table) throws Exception;

  @Override
  public Map<String, String> getProcessParameters() {
    return Maps.newHashMap();
  }

  @Override
  public Map<String, String> getSummary() {
    return summary;
  }

  protected FileStoreTable fileStoreTable() {
    AmoroTable<?> amoroTable = tableRuntime.loadTable();
    Object original = amoroTable.originalTable();
    if (!(original instanceof FileStoreTable)) {
      throw new IllegalStateException(
          "Paimon maintenance requires a FileStoreTable, got " + original.getClass().getName());
    }
    return (FileStoreTable) original;
  }
}
