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

/**
 * One Paimon compaction unit executed by a Spark optimizer. Paimon types stay as bytes so this
 * class can live on the optimizer classpath without the Paimon jar.
 */
public class PaimonCompactInput implements Serializable {

  public static final String EXECUTOR_FACTORY =
      "org.apache.amoro.formats.paimon.maintenance.PaimonCompactExecutorFactory";

  public enum Kind {
    BUCKET,
    APPEND
  }

  private String database;
  private String tableName;
  private String branch;
  private Kind kind;
  private boolean fullCompact;
  private byte[] catalogLoaderBytes;
  private byte[] partitionBytes;
  private int bucket = -1;
  private byte[] appendTaskBytes;
  private String commitUser;

  public PaimonCompactInput() {}

  public String getDatabase() {
    return database;
  }

  public PaimonCompactInput setDatabase(String database) {
    this.database = database;
    return this;
  }

  public String getTableName() {
    return tableName;
  }

  public PaimonCompactInput setTableName(String tableName) {
    this.tableName = tableName;
    return this;
  }

  public String getBranch() {
    return branch;
  }

  public PaimonCompactInput setBranch(String branch) {
    this.branch = branch;
    return this;
  }

  public Kind getKind() {
    return kind;
  }

  public PaimonCompactInput setKind(Kind kind) {
    this.kind = kind;
    return this;
  }

  public boolean isFullCompact() {
    return fullCompact;
  }

  public PaimonCompactInput setFullCompact(boolean fullCompact) {
    this.fullCompact = fullCompact;
    return this;
  }

  public byte[] getCatalogLoaderBytes() {
    return catalogLoaderBytes;
  }

  public PaimonCompactInput setCatalogLoaderBytes(byte[] catalogLoaderBytes) {
    this.catalogLoaderBytes = catalogLoaderBytes;
    return this;
  }

  public byte[] getPartitionBytes() {
    return partitionBytes;
  }

  public PaimonCompactInput setPartitionBytes(byte[] partitionBytes) {
    this.partitionBytes = partitionBytes;
    return this;
  }

  public int getBucket() {
    return bucket;
  }

  public PaimonCompactInput setBucket(int bucket) {
    this.bucket = bucket;
    return this;
  }

  public byte[] getAppendTaskBytes() {
    return appendTaskBytes;
  }

  public PaimonCompactInput setAppendTaskBytes(byte[] appendTaskBytes) {
    this.appendTaskBytes = appendTaskBytes;
    return this;
  }

  public String getCommitUser() {
    return commitUser;
  }

  public PaimonCompactInput setCommitUser(String commitUser) {
    this.commitUser = commitUser;
    return this;
  }

  public String tableLabel() {
    return database + "." + tableName;
  }

  @Override
  public String toString() {
    return "PaimonCompactInput{table="
        + tableLabel()
        + ", kind="
        + kind
        + ", fullCompact="
        + fullCompact
        + ", bucket="
        + bucket
        + '}';
  }
}
