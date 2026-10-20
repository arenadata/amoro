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
import org.apache.amoro.api.OptimizingTaskResult;
import org.apache.amoro.optimizing.TaskProperties;
import org.apache.amoro.paimon.PaimonCompactInput;
import org.apache.amoro.paimon.PaimonCompactSubmission;
import org.apache.amoro.paimon.PaimonCompactSubmitter;
import org.apache.amoro.paimon.PaimonCompactTaskSpec;
import org.apache.amoro.server.persistence.PersistentBase;
import org.apache.amoro.server.persistence.mapper.OptimizingProcessMapper;
import org.apache.amoro.shade.guava32.com.google.common.collect.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/** Serves planned Paimon compact tasks to the existing optimizer poll loop. */
public class PaimonCompactQueue implements PaimonCompactSubmitter {

  private static final Logger LOG = LoggerFactory.getLogger(PaimonCompactQueue.class);
  private static final int FINISHED_LIMIT = 10000;

  private final TaskStore taskStore;
  private final Map<Long, Job> jobs = new ConcurrentHashMap<>();
  private final LinkedHashSet<Long> finished = new LinkedHashSet<>();

  public PaimonCompactQueue() {
    this(new DatabaseTaskStore());
  }

  public PaimonCompactQueue(TaskStore taskStore) {
    this.taskStore = taskStore;
  }

  @Override
  public List<byte[]> submitAndAwait(PaimonCompactSubmission submission) throws Exception {
    Job job = new Job(submission);
    taskStore.replace(submission.getProcessId(), job.rows());
    jobs.put(submission.getProcessId(), job);
    try {
      return job.future.get();
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() == null ? e : e.getCause();
      if (cause instanceof CancellationException) {
        throw (CancellationException) cause;
      }
      if (cause instanceof Exception) {
        throw (Exception) cause;
      }
      throw e;
    } catch (InterruptedException e) {
      cancel(submission.getProcessId());
      Thread.currentThread().interrupt();
      throw e;
    } finally {
      jobs.remove(submission.getProcessId());
      remember(submission.getProcessId());
    }
  }

  @Override
  public void cancel(long processId) {
    Job job = jobs.get(processId);
    if (job != null) {
      job.cancel();
    }
  }

  @Override
  public OptimizingTask poll(
      String optimizerGroup, String optimizerToken, int threadId, int groupThreads) {
    for (Job job : jobs.values()) {
      if (!job.group.equals(optimizerGroup)) {
        continue;
      }
      OptimizingTask task = job.poll(optimizerToken, threadId, groupThreads, taskStore);
      if (task != null) {
        return task;
      }
    }
    return null;
  }

  @Override
  public boolean ack(OptimizingTaskId taskId, String optimizerToken, int threadId) {
    if (taskId == null) {
      return false;
    }
    Job job = jobs.get(taskId.getProcessId());
    if (job == null) {
      return finished(taskId.getProcessId());
    }
    return job.ack(taskId.getTaskId(), optimizerToken, threadId, taskStore);
  }

  @Override
  public boolean complete(OptimizingTaskResult result) {
    if (result == null || result.getTaskId() == null) {
      return false;
    }
    long processId = result.getTaskId().getProcessId();
    Job job = jobs.get(processId);
    if (job == null) {
      return finished(processId);
    }
    job.complete(result, taskStore);
    return true;
  }

  private void remember(long processId) {
    synchronized (finished) {
      finished.add(processId);
      while (finished.size() > FINISHED_LIMIT) {
        Iterator<Long> iterator = finished.iterator();
        if (!iterator.hasNext()) {
          break;
        }
        iterator.next();
        iterator.remove();
      }
    }
  }

  private boolean finished(long processId) {
    synchronized (finished) {
      return finished.contains(processId);
    }
  }

  /** Persists task rows so the dashboard can show task progress. */
  public interface TaskStore {
    TaskStore NOOP =
        new TaskStore() {
          @Override
          public void replace(long processId, List<TaskRow> tasks) {}

          @Override
          public void update(TaskRow task) {}
        };

    void replace(long processId, List<TaskRow> tasks);

    void update(TaskRow task);
  }

  /** One task_runtime row. */
  public static final class TaskRow {
    public long processId;
    public int taskId;
    public long tableId;
    public String partitionData;
    public String status;
    public long startTime;
    public long endTime;
    public long costTime;
    public String failReason;
    public String optimizerToken;
    public int threadId = -1;
    public Map<String, String> properties = Maps.newHashMap();
  }

  private static final class DatabaseTaskStore extends PersistentBase implements TaskStore {
    @Override
    public void replace(long processId, List<TaskRow> tasks) {
      doAs(OptimizingProcessMapper.class, mapper -> mapper.deleteTaskRuntimesByProcess(processId));
      for (TaskRow task : tasks) {
        doAs(OptimizingProcessMapper.class, mapper -> mapper.insertPaimonCompactTask(task));
      }
    }

    @Override
    public void update(TaskRow task) {
      doAs(OptimizingProcessMapper.class, mapper -> mapper.updatePaimonCompactTask(task));
    }
  }

  private static final class Job {
    private final long processId;
    private final long tableId;
    private final String group;
    private final double quota;
    private final List<Task> tasks = new ArrayList<>();
    private final CompletableFuture<List<byte[]>> future = new CompletableFuture<>();

    private Job(PaimonCompactSubmission submission) {
      this.processId = submission.getProcessId();
      this.tableId = submission.getTableId();
      this.group =
          submission.getOptimizerGroup() == null ? "default" : submission.getOptimizerGroup();
      this.quota = submission.getQuota();
      for (PaimonCompactTaskSpec spec : submission.getTasks()) {
        tasks.add(new Task(spec));
      }
    }

    private List<TaskRow> rows() {
      List<TaskRow> rows = new ArrayList<>();
      for (Task task : tasks) {
        rows.add(task.row(processId, tableId));
      }
      return rows;
    }

    private OptimizingTask poll(String token, int threadId, int groupThreads, TaskStore store) {
      synchronized (this) {
        if (future.isDone()) {
          return null;
        }
        int inFlight = 0;
        for (Task task : tasks) {
          if (task.status == TaskRuntime.Status.SCHEDULED
              || task.status == TaskRuntime.Status.ACKED) {
            inFlight++;
          }
        }
        int limit = parallelism(groupThreads);
        if (inFlight >= limit) {
          return null;
        }
        for (Task task : tasks) {
          if (task.status == TaskRuntime.Status.PLANNED) {
            task.status = TaskRuntime.Status.SCHEDULED;
            task.token = token;
            task.threadId = threadId;
            task.startTime = System.currentTimeMillis();
            task.attempt++;
            store.update(task.row(processId, tableId));
            return task.protocol(processId);
          }
        }
        return null;
      }
    }

    private boolean ack(int taskId, String token, int threadId, TaskStore store) {
      synchronized (this) {
        Task task = find(taskId);
        if (task == null || future.isDone()) {
          return true;
        }
        if (task.status != TaskRuntime.Status.SCHEDULED
            || task.threadId != threadId
            || token == null
            || !token.equals(task.token)) {
          throw new IllegalStateException(
              "Paimon compact task "
                  + processId
                  + "/"
                  + taskId
                  + " is not scheduled for this optimizer");
        }
        task.status = TaskRuntime.Status.ACKED;
        store.update(task.row(processId, tableId));
        return true;
      }
    }

    private void complete(OptimizingTaskResult result, TaskStore store) {
      synchronized (this) {
        if (future.isDone()) {
          return;
        }
        Task task = find(result.getTaskId().getTaskId());
        if (task == null || task.status != TaskRuntime.Status.ACKED) {
          LOG.warn(
              "Ignoring stale completion for paimon compact task {}/{}",
              processId,
              result.getTaskId().getTaskId());
          return;
        }
        task.endTime = System.currentTimeMillis();
        task.costTime = Math.max(0L, task.endTime - task.startTime);
        if (result.getErrorMessage() != null) {
          task.status = TaskRuntime.Status.FAILED;
          task.failReason = result.getErrorMessage();
          cancelPlanned();
          store.update(task.row(processId, tableId));
          future.completeExceptionally(new RuntimeException(result.getErrorMessage()));
          return;
        }
        task.status = TaskRuntime.Status.SUCCESS;
        task.output = result.getTaskOutput();
        store.update(task.row(processId, tableId));
        if (allSucceeded()) {
          List<byte[]> outputs = new ArrayList<>();
          for (Task finishedTask : tasks) {
            outputs.add(finishedTask.output == null ? new byte[0] : finishedTask.output);
          }
          future.complete(outputs);
        }
      }
    }

    private void cancel() {
      synchronized (this) {
        if (future.isDone()) {
          return;
        }
        cancelPlanned();
        future.completeExceptionally(new CancellationException("Paimon compaction canceled"));
      }
    }

    private void cancelPlanned() {
      for (Task task : tasks) {
        if (task.status == TaskRuntime.Status.PLANNED) {
          task.status = TaskRuntime.Status.CANCELED;
        }
      }
    }

    private boolean allSucceeded() {
      for (Task task : tasks) {
        if (task.status != TaskRuntime.Status.SUCCESS) {
          return false;
        }
      }
      return !tasks.isEmpty();
    }

    private Task find(int taskId) {
      for (Task task : tasks) {
        if (task.id == taskId) {
          return task;
        }
      }
      return null;
    }

    private int parallelism(int groupThreads) {
      int threads = Math.max(groupThreads, 1);
      int limit = (int) Math.floor(threads * Math.max(quota, 0D));
      return Math.max(limit, 1);
    }
  }

  private static final class Task {
    private final int id;
    private final String partition;
    private final byte[] input;
    private TaskRuntime.Status status = TaskRuntime.Status.PLANNED;
    private String token;
    private int threadId = -1;
    private long startTime;
    private long endTime;
    private long costTime;
    private String failReason;
    private byte[] output;
    private long attempt;

    private Task(PaimonCompactTaskSpec spec) {
      this.id = spec.getTaskId();
      this.partition = spec.getPartition();
      this.input = spec.getTaskInput();
    }

    private OptimizingTask protocol(long processId) {
      OptimizingTask task = new OptimizingTask(new OptimizingTaskId(processId, id));
      task.setTaskInput(input);
      Map<String, String> properties = Maps.newHashMap();
      properties.put(
          TaskProperties.TASK_EXECUTOR_FACTORY_IMPL, PaimonCompactInput.EXECUTOR_FACTORY);
      properties.put(TaskProperties.TASK_ATTEMPT_ID, Long.toString(attempt));
      properties.put(TaskProperties.PROCESS_ID, Long.toString(processId));
      task.setProperties(properties);
      return task;
    }

    private TaskRow row(long processId, long tableId) {
      TaskRow row = new TaskRow();
      row.processId = processId;
      row.taskId = id;
      row.tableId = tableId;
      row.partitionData = partition;
      row.status = status.name();
      row.startTime = startTime;
      row.endTime = endTime;
      row.costTime = costTime;
      row.failReason = failReason;
      row.optimizerToken = token;
      row.threadId = threadId;
      row.properties = Maps.newHashMap();
      row.properties.put(
          TaskProperties.TASK_EXECUTOR_FACTORY_IMPL, PaimonCompactInput.EXECUTOR_FACTORY);
      row.properties.put(TaskProperties.TASK_ATTEMPT_ID, Long.toString(attempt));
      return row;
    }
  }
}
