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

package org.apache.amoro.server.catalog;

import static org.apache.amoro.server.table.internal.InternalTableConstants.CHANGE_STORE_SEPARATOR;
import static org.apache.amoro.server.table.internal.InternalTableConstants.CHANGE_STORE_TABLE_NAME_SUFFIX;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalListener;
import org.apache.amoro.AmoroTable;
import org.apache.amoro.TableFormat;
import org.apache.amoro.api.CatalogMeta;
import org.apache.amoro.config.Configurations;
import org.apache.amoro.exception.ObjectNotExistsException;
import org.apache.amoro.formats.iceberg.IcebergTable;
import org.apache.amoro.server.table.TableMetadata;
import org.apache.amoro.server.table.internal.InternalIcebergCreator;
import org.apache.amoro.server.table.internal.InternalIcebergHandler;
import org.apache.amoro.server.table.internal.InternalTableCreator;
import org.apache.amoro.server.table.internal.InternalTableHandler;
import org.apache.amoro.shade.guava32.com.google.common.base.Preconditions;
import org.apache.amoro.utils.CatalogUtil;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.rest.requests.CreateTableRequest;

public class InternalCatalogImpl extends InternalCatalog {

  final Cache<AmoroTable<?>, FileIO> fileIOCloser;

  protected InternalCatalogImpl(CatalogMeta metadata, Configurations serverConfiguration) {
    super(metadata);
    Preconditions.checkNotNull(serverConfiguration, "server configuration is null");
    this.fileIOCloser = newFileIOCloser();
  }

  @Override
  public AmoroTable<?> loadTable(String database, String tableName) {
    Preconditions.checkArgument(
        !isChangeStoreName(tableName), "table name is invalid for load table");

    InternalTableHandler<TableOperations> handler;
    try {
      handler = newTableHandler(database, tableName);
    } catch (ObjectNotExistsException e) {
      return null;
    }
    if (TableFormat.ICEBERG.equals(handler.tableMetadata().getFormat())) {
      return loadIcebergTable(database, tableName, handler);
    }
    throw new IllegalArgumentException(
        "Unsupported table format:" + handler.tableMetadata().getFormat());
  }

  private AmoroTable<?> loadIcebergTable(
      String database, String tableName, InternalTableHandler<TableOperations> handler) {
    TableMetadata tableMetadata = handler.tableMetadata();
    TableOperations ops = handler.newTableOperator();

    BaseTable table =
        new BaseTable(
            ops,
            TableIdentifier.of(
                    tableMetadata.getTableIdentifier().getDatabase(),
                    tableMetadata.getTableIdentifier().getTableName())
                .toString());
    org.apache.amoro.table.TableIdentifier tableIdentifier =
        org.apache.amoro.table.TableIdentifier.of(name(), database, tableName);
    AmoroTable<?> amoroTable =
        IcebergTable.newIcebergTable(
            tableIdentifier,
            table,
            CatalogUtil.buildMetaStore(getMetadata()),
            getMetadata().getCatalogProperties());
    fileIOCloser.put(amoroTable, ops.io());
    return amoroTable;
  }

  @Override
  public InternalTableCreator newTableCreator(
      String database, String tableName, TableFormat format, CreateTableRequest creatorArguments) {
    if (tableExists(database, tableName)) {
      throw new AlreadyExistsException(
          "Table " + name() + "." + database + "." + tableName + " already exists.");
    }
    if (TableFormat.ICEBERG.equals(format)) {
      return new InternalIcebergCreator(getMetadata(), database, tableName, creatorArguments);
    }
    throw new IllegalArgumentException("Unsupported table format:" + format);
  }

  @Override
  @SuppressWarnings("unchecked")
  public InternalTableHandler<TableOperations> newTableHandler(String database, String tableName) {
    String realTableName = realTableName(tableName);
    TableMetadata metadata = loadTableMetadata(database, realTableName);
    if (TableFormat.ICEBERG.equals(metadata.getFormat())) {
      return new InternalIcebergHandler(getMetadata(), metadata);
    }
    throw new IllegalArgumentException("Unsupported table format:" + metadata.getFormat());
  }

  private String realTableName(String tableStoreName) {
    if (isChangeStoreName(tableStoreName)) {
      return tableStoreName.substring(
          0, tableStoreName.length() - CHANGE_STORE_TABLE_NAME_SUFFIX.length());
    }
    return tableStoreName;
  }

  private boolean isChangeStoreName(String tableName) {
    String separator = CHANGE_STORE_SEPARATOR;
    if (!tableName.contains(separator)) {
      return false;
    }
    Preconditions.checkArgument(
        tableName.indexOf(separator) == tableName.lastIndexOf(separator)
            && tableName.endsWith(CHANGE_STORE_TABLE_NAME_SUFFIX),
        "illegal table name: %s, %s is not allowed in table name.",
        tableName,
        separator);

    return true;
  }

  private Cache<AmoroTable<?>, FileIO> newFileIOCloser() {
    return Caffeine.newBuilder()
        .weakKeys()
        .removalListener(
            (RemovalListener<AmoroTable<?>, FileIO>)
                (tbl, fileIO, cause) -> {
                  if (null != fileIO) {
                    fileIO.close();
                  }
                })
        .build();
  }
}
