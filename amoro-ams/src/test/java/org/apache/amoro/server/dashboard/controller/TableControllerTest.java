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

package org.apache.amoro.server.dashboard.controller;

import org.apache.amoro.table.descriptor.PartitionBaseInfo;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class TableControllerTest {

  /**
   * Creates test partition data with varying values for sorting tests.
   *
   * @return List of test partitions
   */
  private List<PartitionBaseInfo> createTestPartitions() {
    return Arrays.asList(
        new PartitionBaseInfo("partition_a", 1, 100, 1000, 1609459200000L),
        new PartitionBaseInfo("partition_b", 2, 200, 2000, 1609545600000L),
        new PartitionBaseInfo("partition_c", 3, 150, 1500, 1609372800000L),
        new PartitionBaseInfo("partition_d", 1, 50, 500, 1609632000000L)
    );
  }

  /**
   * Invokes the private getPartitionComparator method via reflection.
   *
   * @param sortBy field name to sort by
   * @param sortOrder sort order ("asc" or "desc")
   * @return Comparator for PartitionBaseInfo
   * @throws Exception if reflection fails
   */
  private Comparator<PartitionBaseInfo> invokeGetPartitionComparator(String sortBy, String sortOrder) throws Exception {
    TableController controller = new TableController(null, null, null, null);
    Method method = TableController.class.getDeclaredMethod("getPartitionComparator", String.class, String.class);
    method.setAccessible(true);
    try {
      return (Comparator<PartitionBaseInfo>) method.invoke(controller, sortBy, sortOrder);
    } catch (java.lang.reflect.InvocationTargetException e) {
      // Unwrap the exception thrown by the invoked method
      Throwable cause = e.getCause();
      if (cause instanceof Exception) {
        throw (Exception) cause;
      }
      throw e;
    }
  }

  /** Test sorting by partition name in ascending order. */
  @Test
  public void testSortByPartitionAsc() throws Exception {
    List<PartitionBaseInfo> partitions = createTestPartitions();
    Comparator<PartitionBaseInfo> comparator = invokeGetPartitionComparator("partition", "asc");
    
    List<PartitionBaseInfo> sorted = partitions.stream()
        .sorted(comparator)
        .collect(Collectors.toList());
    
    Assert.assertEquals("partition_a", sorted.get(0).getPartition());
    Assert.assertEquals("partition_d", sorted.get(3).getPartition());
  }

  /** Test sorting by partition name in descending order. */
  @Test
  public void testSortByPartitionDesc() throws Exception {
    List<PartitionBaseInfo> partitions = createTestPartitions();
    Comparator<PartitionBaseInfo> comparator = invokeGetPartitionComparator("partition", "desc");
    
    List<PartitionBaseInfo> sorted = partitions.stream()
        .sorted(comparator)
        .collect(Collectors.toList());
    
    Assert.assertEquals("partition_d", sorted.get(0).getPartition());
    Assert.assertEquals("partition_a", sorted.get(3).getPartition());
  }

  /** Test sorting by file count in ascending order. */
  @Test
  public void testSortByFileCountAsc() throws Exception {
    List<PartitionBaseInfo> partitions = createTestPartitions();
    Comparator<PartitionBaseInfo> comparator = invokeGetPartitionComparator("fileCount", "asc");
    
    List<PartitionBaseInfo> sorted = partitions.stream()
        .sorted(comparator)
        .collect(Collectors.toList());
    
    Assert.assertEquals(50, sorted.get(0).getFileCount());
    Assert.assertEquals(200, sorted.get(3).getFileCount());
  }

  /** Test sorting by file count in descending order. */
  @Test
  public void testSortByFileCountDesc() throws Exception {
    List<PartitionBaseInfo> partitions = createTestPartitions();
    Comparator<PartitionBaseInfo> comparator = invokeGetPartitionComparator("fileCount", "desc");
    
    List<PartitionBaseInfo> sorted = partitions.stream()
        .sorted(comparator)
        .collect(Collectors.toList());
    
    Assert.assertEquals(200, sorted.get(0).getFileCount());
    Assert.assertEquals(50, sorted.get(3).getFileCount());
  }

  /** Test sorting by file size in ascending order. */
  @Test
  public void testSortByFileSizeAsc() throws Exception {
    List<PartitionBaseInfo> partitions = createTestPartitions();
    Comparator<PartitionBaseInfo> comparator = invokeGetPartitionComparator("fileSize", "asc");
    
    List<PartitionBaseInfo> sorted = partitions.stream()
        .sorted(comparator)
        .collect(Collectors.toList());
    
    Assert.assertEquals(500, sorted.get(0).getFileSize());
    Assert.assertEquals(2000, sorted.get(3).getFileSize());
  }

  /** Test sorting by file size in descending order. */
  @Test
  public void testSortByFileSizeDesc() throws Exception {
    List<PartitionBaseInfo> partitions = createTestPartitions();
    Comparator<PartitionBaseInfo> comparator = invokeGetPartitionComparator("fileSize", "desc");
    
    List<PartitionBaseInfo> sorted = partitions.stream()
        .sorted(comparator)
        .collect(Collectors.toList());
    
    Assert.assertEquals(2000, sorted.get(0).getFileSize());
    Assert.assertEquals(500, sorted.get(3).getFileSize());
  }

  /** Test sorting by last commit time in ascending order. */
  @Test
  public void testSortByLastCommitTimeAsc() throws Exception {
    List<PartitionBaseInfo> partitions = createTestPartitions();
    Comparator<PartitionBaseInfo> comparator = invokeGetPartitionComparator("lastCommitTime", "asc");
    
    List<PartitionBaseInfo> sorted = partitions.stream()
        .sorted(comparator)
        .collect(Collectors.toList());
    
    Assert.assertEquals(1609372800000L, sorted.get(0).getLastCommitTime());
    Assert.assertEquals(1609632000000L, sorted.get(3).getLastCommitTime());
  }

  /** Test sorting by last commit time in descending order. */
  @Test
  public void testSortByLastCommitTimeDesc() throws Exception {
    List<PartitionBaseInfo> partitions = createTestPartitions();
    Comparator<PartitionBaseInfo> comparator = invokeGetPartitionComparator("lastCommitTime", "desc");
    
    List<PartitionBaseInfo> sorted = partitions.stream()
        .sorted(comparator)
        .collect(Collectors.toList());
    
    Assert.assertEquals(1609632000000L, sorted.get(0).getLastCommitTime());
    Assert.assertEquals(1609372800000L, sorted.get(3).getLastCommitTime());
  }

  /** Test sorting by spec ID in ascending order. */
  @Test
  public void testSortBySpecIdAsc() throws Exception {
    List<PartitionBaseInfo> partitions = createTestPartitions();
    Comparator<PartitionBaseInfo> comparator = invokeGetPartitionComparator("specId", "asc");
    
    List<PartitionBaseInfo> sorted = partitions.stream()
        .sorted(comparator)
        .collect(Collectors.toList());
    
    Assert.assertEquals(1, sorted.get(0).getSpecId());
    Assert.assertEquals(3, sorted.get(3).getSpecId());
  }

  /** Test sorting by spec ID in descending order. */
  @Test
  public void testSortBySpecIdDesc() throws Exception {
    List<PartitionBaseInfo> partitions = createTestPartitions();
    Comparator<PartitionBaseInfo> comparator = invokeGetPartitionComparator("specId", "desc");
    
    List<PartitionBaseInfo> sorted = partitions.stream()
        .sorted(comparator)
        .collect(Collectors.toList());
    
    Assert.assertEquals(3, sorted.get(0).getSpecId());
    Assert.assertEquals(1, sorted.get(3).getSpecId());
  }

  /** Test that invalid sortBy parameter throws IllegalArgumentException. */
  @Test(expected = IllegalArgumentException.class)
  public void testInvalidSortByParameter() throws Exception {
    invokeGetPartitionComparator("invalidField", "asc");
  }

  /** Test that null sortBy parameter throws IllegalArgumentException. */
  @Test(expected = IllegalArgumentException.class)
  public void testNullSortByParameter() throws Exception {
    invokeGetPartitionComparator(null, "asc");
  }

  /** Test that all valid sort fields work correctly. */
  @Test
  public void testValidSortFields() throws Exception {
    invokeGetPartitionComparator("partition", "asc");
    invokeGetPartitionComparator("specId", "desc");
    invokeGetPartitionComparator("fileCount", "asc");
    invokeGetPartitionComparator("fileSize", "desc");
    invokeGetPartitionComparator("lastCommitTime", "asc");
  }

  /** Test that sortOrder parameter is case insensitive. */
  @Test
  public void testSortOrderCaseInsensitive() throws Exception {
    List<PartitionBaseInfo> partitions = createTestPartitions();
    
    Comparator<PartitionBaseInfo> comparator1 = invokeGetPartitionComparator("fileCount", "DESC");
    Comparator<PartitionBaseInfo> comparator2 = invokeGetPartitionComparator("fileCount", "desc");
    
    List<PartitionBaseInfo> sorted1 = partitions.stream()
        .sorted(comparator1)
        .collect(Collectors.toList());
    List<PartitionBaseInfo> sorted2 = partitions.stream()
        .sorted(comparator2)
        .collect(Collectors.toList());
    
    Assert.assertEquals(sorted1.get(0).getFileCount(), sorted2.get(0).getFileCount());
  }
}
