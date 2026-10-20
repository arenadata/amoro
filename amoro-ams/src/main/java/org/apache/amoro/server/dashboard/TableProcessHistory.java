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

package org.apache.amoro.server.dashboard;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.apache.amoro.AmoroTable;
import org.apache.amoro.ServerTableIdentifier;
import org.apache.amoro.optimizing.MetricsSummary;
import org.apache.amoro.process.ProcessStatus;
import org.apache.amoro.process.ProcessTaskStatus;
import org.apache.amoro.server.optimizing.OptimizingTaskMeta;
import org.apache.amoro.server.optimizing.TaskRuntime;
import org.apache.amoro.server.persistence.PersistentBase;
import org.apache.amoro.server.persistence.mapper.OptimizingProcessMapper;
import org.apache.amoro.server.persistence.mapper.TableMetaMapper;
import org.apache.amoro.server.persistence.mapper.TableProcessMapper;
import org.apache.amoro.server.process.TableProcessMeta;
import org.apache.amoro.table.TableIdentifier;
import org.apache.amoro.table.descriptor.FilesStatistics;
import org.apache.amoro.table.descriptor.FormatTableDescriptor;
import org.apache.amoro.table.descriptor.OptimizingProcessInfo;
import org.apache.amoro.table.descriptor.OptimizingTaskInfo;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Reads maintenance history from {@code table_process} for formats that do not use Iceberg
 * optimizing.
 */
public final class TableProcessHistory extends PersistentBase {

  private static final TableProcessHistory INSTANCE = new TableProcessHistory();

  private TableProcessHistory() {}

  public static Pair<List<OptimizingProcessInfo>, Integer> list(
      AmoroTable<?> amoroTable,
      FormatTableDescriptor descriptor,
      String type,
      String processCategory,
      ProcessStatus status,
      int limit,
      int offset) {
    return INSTANCE.listProcesses(
        amoroTable, descriptor, type, processCategory, status, limit, offset);
  }

  public static List<OptimizingTaskInfo> tasks(String processId) {
    return INSTANCE.listTasks(processId);
  }

  private Pair<List<OptimizingProcessInfo>, Integer> listProcesses(
      AmoroTable<?> amoroTable,
      FormatTableDescriptor descriptor,
      String type,
      String processCategory,
      ProcessStatus status,
      int limit,
      int offset) {
    if (limit <= 0) {
      return Pair.of(Collections.emptyList(), 0);
    }
    TableIdentifier tableIdentifier = amoroTable.id();
    ServerTableIdentifier identifier =
        getAs(
            TableMetaMapper.class,
            mapper ->
                mapper.selectTableIdentifier(
                    tableIdentifier.getCatalog(),
                    tableIdentifier.getDatabase(),
                    tableIdentifier.getTableName()));
    if (identifier == null) {
      return Pair.of(Collections.emptyList(), 0);
    }
    List<String> includeTypes = null;
    if (StringUtils.isBlank(type)) {
      if (processCategory == null) {
        return Pair.of(Collections.emptyList(), 0);
      }
      includeTypes = descriptor.getProcessTypesByCategory(processCategory);
      if (includeTypes == null || includeTypes.isEmpty()) {
        return Pair.of(Collections.emptyList(), 0);
      }
    }
    int pageNumber = (offset / limit) + 1;
    List<TableProcessMeta> processMetaList;
    int total;
    final List<String> types = includeTypes;
    try (Page<?> ignored = PageHelper.startPage(pageNumber, limit, true)) {
      processMetaList =
          getAs(
              TableProcessMapper.class,
              mapper -> mapper.listProcessMeta(identifier.getId(), type, types, status));
      PageInfo<TableProcessMeta> pageInfo = new PageInfo<>(processMetaList);
      total = (int) pageInfo.getTotal();
      if (pageInfo.getSize() == 0) {
        return Pair.of(Collections.emptyList(), 0);
      }
    }
    List<Long> processIds =
        processMetaList.stream().map(TableProcessMeta::getProcessId).collect(Collectors.toList());
    Map<Long, List<OptimizingTaskMeta>> tasks =
        getAs(OptimizingProcessMapper.class, mapper -> mapper.selectOptimizeTaskMetas(processIds))
            .stream()
            .collect(Collectors.groupingBy(OptimizingTaskMeta::getProcessId));
    List<OptimizingProcessInfo> infos =
        processMetaList.stream()
            .map(meta -> toProcessInfo(identifier, meta, tasks.get(meta.getProcessId())))
            .collect(Collectors.toList());
    return Pair.of(infos, total);
  }

  private List<OptimizingTaskInfo> listTasks(String processId) {
    long id;
    try {
      id = Long.parseLong(processId);
    } catch (NumberFormatException e) {
      return Collections.emptyList();
    }
    List<OptimizingTaskMeta> taskMetas =
        getAs(
            OptimizingProcessMapper.class,
            mapper -> mapper.selectOptimizeTaskMetas(Collections.singletonList(id)));
    if (CollectionUtils.isEmpty(taskMetas)) {
      return Collections.emptyList();
    }
    return taskMetas.stream().map(TableProcessHistory::toTaskInfo).collect(Collectors.toList());
  }

  private static OptimizingProcessInfo toProcessInfo(
      ServerTableIdentifier identifier, TableProcessMeta meta, List<OptimizingTaskMeta> taskMetas) {
    OptimizingProcessInfo result = new OptimizingProcessInfo();
    if (taskMetas != null) {
      int successTasks = 0;
      int runningTasks = 0;
      for (OptimizingTaskMeta taskMeta : taskMetas) {
        TaskRuntime.Status taskStatus = taskMeta.getStatus();
        if (taskStatus == TaskRuntime.Status.SUCCESS) {
          successTasks++;
        } else if (taskStatus == TaskRuntime.Status.SCHEDULED
            || taskStatus == TaskRuntime.Status.ACKED) {
          runningTasks++;
        }
      }
      result.setTotalTasks(taskMetas.size());
      result.setSuccessTasks(successTasks);
      result.setRunningTasks(runningTasks);
    }
    Map<String, String> summaryMap =
        meta.getSummary() == null ? Collections.emptyMap() : meta.getSummary();
    MetricsSummary summary = MetricsSummary.fromMap(summaryMap);
    result.setInputFiles(summary.getInputFilesStatistics());
    result.setOutputFiles(summary.getOutputFilesStatistics());
    result.setTableId(meta.getTableId());
    result.setCatalogName(identifier.getCatalog());
    result.setDbName(identifier.getDatabase());
    result.setTableName(identifier.getTableName());
    result.setProcessId(String.valueOf(meta.getProcessId()));
    result.setStartTime(meta.getCreateTime());
    result.setOptimizingType(meta.getProcessType());
    result.setStatus(ProcessStatus.valueOf(meta.getStatus().name()));
    result.setFailReason(meta.getFailMessage());
    result.setDuration(
        meta.getFinishTime() > 0
            ? meta.getFinishTime() - meta.getCreateTime()
            : System.currentTimeMillis() - meta.getCreateTime());
    result.setFinishTime(meta.getFinishTime());
    result.setSummary(summaryMap);
    return result;
  }

  private static OptimizingTaskInfo toTaskInfo(OptimizingTaskMeta taskMeta) {
    MetricsSummary metrics =
        taskMeta.getMetricsSummary() == null ? new MetricsSummary() : taskMeta.getMetricsSummary();
    FilesStatistics input = metrics.getInputFilesStatistics();
    FilesStatistics output = metrics.getOutputFilesStatistics();
    return new OptimizingTaskInfo(
        taskMeta.getTableId(),
        String.valueOf(taskMeta.getProcessId()),
        taskMeta.getTaskId(),
        taskMeta.getPartitionData(),
        ProcessTaskStatus.valueOf(taskMeta.getStatus().name()),
        taskMeta.getRetryNum(),
        taskMeta.getOptimizerToken(),
        taskMeta.getThreadId(),
        taskMeta.getStartTime(),
        taskMeta.getEndTime(),
        taskMeta.getCostTime(),
        taskMeta.getFailReason(),
        input,
        output,
        metrics.summaryAsMap(true),
        taskMeta.getProperties());
  }
}
