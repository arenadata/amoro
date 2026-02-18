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

import org.apache.amoro.exception.BadRequestException;
import org.apache.amoro.shade.guava32.com.google.common.collect.Range;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;

/**
 * Unit tests for {@link TableController#buildCommitTimeRange(Long, Long)}. Covers date range
 * filtering for snapshots in the getTableSnapshots API (ADH-7495): both parameters, only startTime,
 * only endTime, or neither (default behavior).
 */
public class TableControllerCommitTimeRangeTest {

  /**
   * Invokes the private {@code buildCommitTimeRange} method via reflection.
   *
   * @param startTime range start in seconds (inclusive), or null if not specified
   * @param endTime range end in seconds (inclusive), or null if not specified
   * @return the commit time range in milliseconds
   * @throws Exception if reflection fails or the controller throws (e.g. BadRequestException)
   */
  @SuppressWarnings("unchecked")
  private Range<Long> invokeBuildCommitTimeRange(Long startTime, Long endTime) throws Exception {
    TableController controller = new TableController(null, null, null, null);
    Method method =
        TableController.class.getDeclaredMethod("buildCommitTimeRange", Long.class, Long.class);
    method.setAccessible(true);
    try {
      return (Range<Long>) method.invoke(controller, startTime, endTime);
    } catch (java.lang.reflect.InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof Exception) {
        throw (Exception) cause;
      }
      throw e;
    }
  }

  /** Both startTime and endTime provided; range is [startTime, endTime] inclusive (in ms). */
  @Test
  public void testBuildCommitTimeRangeClosed() throws Exception {
    Range<Long> range = invokeBuildCommitTimeRange(2L, 3L);

    Assert.assertTrue(range.contains(2000L));
    Assert.assertTrue(range.contains(2500L));
    Assert.assertTrue(range.contains(3000L));
    Assert.assertFalse(range.contains(1999L));
    Assert.assertFalse(range.contains(3001L));
  }

  /** Only startTime provided; snapshots with commitTime >= startTime. */
  @Test
  public void testBuildCommitTimeRangeAtLeast() throws Exception {
    Range<Long> range = invokeBuildCommitTimeRange(2L, null);

    Assert.assertTrue(range.contains(2000L));
    Assert.assertTrue(range.contains(Long.MAX_VALUE));
    Assert.assertFalse(range.contains(1999L));
  }

  /** Only endTime provided; snapshots with commitTime <= endTime. */
  @Test
  public void testBuildCommitTimeRangeAtMost() throws Exception {
    Range<Long> range = invokeBuildCommitTimeRange(null, 3L);

    Assert.assertTrue(range.contains(3000L));
    Assert.assertTrue(range.contains(Long.MIN_VALUE));
    Assert.assertFalse(range.contains(3001L));
  }

  /** Neither startTime nor endTime provided; no date filtering (Range.all()). */
  @Test
  public void testBuildCommitTimeRangeAll() throws Exception {
    Range<Long> range = invokeBuildCommitTimeRange(null, null);

    Assert.assertTrue(range.contains(Long.MIN_VALUE));
    Assert.assertTrue(range.contains(Long.MAX_VALUE));
  }

  /** startTime > endTime; expects BadRequestException (4xx). */
  @Test(expected = BadRequestException.class)
  public void testBuildCommitTimeRangeInvalidOrder() throws Exception {
    invokeBuildCommitTimeRange(4L, 3L);
  }

  /** startTime causes overflow when converting to milliseconds; expects BadRequestException. */
  @Test(expected = BadRequestException.class)
  public void testBuildCommitTimeRangeOverflow() throws Exception {
    invokeBuildCommitTimeRange(Long.MAX_VALUE, null);
  }

  /** Edge case: startTime == endTime; range is a single point in milliseconds. */
  @Test
  public void testBuildCommitTimeRangeSinglePoint() throws Exception {
    Range<Long> range = invokeBuildCommitTimeRange(2L, 2L);

    Assert.assertTrue(range.contains(2000L));
    Assert.assertFalse(range.contains(1999L));
    Assert.assertFalse(range.contains(2001L));
  }

  /** endTime causes overflow when converting to milliseconds; expects BadRequestException. */
  @Test(expected = BadRequestException.class)
  public void testBuildCommitTimeRangeOverflowEndTime() throws Exception {
    invokeBuildCommitTimeRange(null, Long.MAX_VALUE);
  }
}
