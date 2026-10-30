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

import org.apache.paimon.catalog.CatalogLoader;
import org.apache.paimon.io.DataInputDeserializer;
import org.apache.paimon.io.DataOutputSerializer;
import org.apache.paimon.table.sink.CommitMessage;
import org.apache.paimon.table.sink.CommitMessageSerializer;
import org.apache.paimon.utils.InstantiationUtil;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/** Byte codecs shared by the planner, the optimizer executor, and the AMS commit. */
final class PaimonCommitMessages {

  private PaimonCommitMessages() {}

  static byte[] serializeLoader(CatalogLoader loader) {
    try {
      return InstantiationUtil.serializeObject(loader);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to serialize Paimon catalog loader", e);
    }
  }

  static CatalogLoader deserializeLoader(byte[] bytes) {
    try {
      return InstantiationUtil.deserializeObject(bytes, CatalogLoader.class.getClassLoader());
    } catch (IOException | ClassNotFoundException e) {
      throw new IllegalStateException("Failed to deserialize Paimon catalog loader", e);
    }
  }

  static byte[] serialize(List<CommitMessage> messages) throws IOException {
    if (messages == null || messages.isEmpty()) {
      return new byte[0];
    }
    CommitMessageSerializer serializer = new CommitMessageSerializer();
    DataOutputSerializer output = new DataOutputSerializer(256);
    serializer.serializeList(messages, output);
    return output.getCopyOfBuffer();
  }

  static List<CommitMessage> deserialize(byte[] bytes) throws IOException {
    if (bytes == null || bytes.length == 0) {
      return Collections.emptyList();
    }
    CommitMessageSerializer serializer = new CommitMessageSerializer();
    return serializer.deserializeList(serializer.getVersion(), new DataInputDeserializer(bytes));
  }
}
