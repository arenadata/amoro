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

import static org.apache.amoro.properties.CatalogMetaProperties.CATALOG_TYPE_CUSTOM;
import static org.apache.amoro.properties.CatalogMetaProperties.CATALOG_TYPE_FILESYSTEM;
import static org.apache.amoro.properties.CatalogMetaProperties.CATALOG_TYPE_GLUE;
import static org.apache.amoro.properties.CatalogMetaProperties.CATALOG_TYPE_HADOOP;
import static org.apache.amoro.properties.CatalogMetaProperties.CATALOG_TYPE_HIVE;
import static org.apache.amoro.properties.CatalogMetaProperties.CATALOG_TYPE_REST;

import org.apache.amoro.TableFormat;
import org.apache.amoro.api.CatalogMeta;
import org.apache.amoro.config.Configurations;
import org.apache.amoro.properties.CatalogMetaProperties;
import org.apache.amoro.shade.guava32.com.google.common.base.Preconditions;
import org.apache.amoro.shade.guava32.com.google.common.collect.ImmutableMap;
import org.apache.amoro.shade.guava32.com.google.common.collect.Sets;
import org.apache.amoro.utils.CatalogUtil;

import java.util.Map;
import java.util.Set;

public class CatalogBuilder {

  /** matrix of catalog type and supported table formats */
  private static final Map<String, Set<TableFormat>> formatSupportedMatrix =
      ImmutableMap.of(
          CATALOG_TYPE_HADOOP,
          Sets.newHashSet(TableFormat.ICEBERG, TableFormat.PAIMON),
          CATALOG_TYPE_FILESYSTEM,
          Sets.newHashSet(TableFormat.ICEBERG, TableFormat.PAIMON),
          CATALOG_TYPE_GLUE,
          Sets.newHashSet(TableFormat.ICEBERG),
          CATALOG_TYPE_REST,
          Sets.newHashSet(TableFormat.ICEBERG),
          CATALOG_TYPE_CUSTOM,
          Sets.newHashSet(TableFormat.ICEBERG),
          CATALOG_TYPE_HIVE,
          Sets.newHashSet(TableFormat.ICEBERG, TableFormat.PAIMON));

  /**
   * Reject catalogs that use AMS as a metastore or a mixed table format. The catalog name is
   * included so operators can find the row to remove.
   */
  public static void ensureSupported(CatalogMeta catalogMeta) {
    String name = catalogMeta.getCatalogName();
    if (CatalogMetaProperties.CATALOG_TYPE_AMS.equalsIgnoreCase(catalogMeta.getCatalogType())) {
      throw new UnsupportedCatalogException(
          "Catalog " + name + " uses metastore type ams, which is no longer supported");
    }
    Map<String, String> properties = catalogMeta.getCatalogProperties();
    if (properties == null) {
      return;
    }
    String formats = properties.get(CatalogMetaProperties.TABLE_FORMATS);
    if (formats == null) {
      return;
    }
    for (String format : formats.split(",")) {
      String normalized = format.trim();
      if (normalized.equalsIgnoreCase("MIXED_ICEBERG")
          || normalized.equalsIgnoreCase("MIXED_HIVE")) {
        throw new UnsupportedCatalogException(
            "Catalog "
                + name
                + " uses table format "
                + normalized
                + ", which is no longer supported");
      }
    }
  }

  public static ServerCatalog buildServerCatalog(
      CatalogMeta catalogMeta, Configurations serverConfiguration) {
    ensureSupported(catalogMeta);
    Preconditions.checkNotNull(serverConfiguration, "server configuration is null");
    String type = catalogMeta.getCatalogType();
    Set<TableFormat> tableFormats = CatalogUtil.tableFormats(catalogMeta);

    Preconditions.checkState(
        formatSupportedMatrix.containsKey(type), "unsupported catalog type: %s", type);

    Set<TableFormat> supportedFormats = formatSupportedMatrix.get(type);
    Preconditions.checkState(
        supportedFormats.containsAll(tableFormats),
        "Table format %s is not supported for metastore type: %s",
        tableFormats,
        type);

    switch (type) {
      case CATALOG_TYPE_HADOOP:
      case CATALOG_TYPE_FILESYSTEM:
      case CATALOG_TYPE_GLUE:
      case CATALOG_TYPE_REST:
      case CATALOG_TYPE_CUSTOM:
      case CATALOG_TYPE_HIVE:
        return new ExternalCatalog(catalogMeta);
      default:
        throw new IllegalStateException("unsupported catalog type:" + type);
    }
  }
}
