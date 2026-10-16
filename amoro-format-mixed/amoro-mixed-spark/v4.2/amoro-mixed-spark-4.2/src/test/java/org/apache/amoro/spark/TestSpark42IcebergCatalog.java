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

package org.apache.amoro.spark;

import org.apache.amoro.TableFormat;
import org.apache.amoro.TestAms;
import org.apache.amoro.UnifiedCatalog;
import org.apache.amoro.UnifiedCatalogLoader;
import org.apache.amoro.api.CatalogMeta;
import org.apache.amoro.catalog.BasicCatalogTestHelper;
import org.apache.amoro.properties.CatalogMetaProperties;
import org.apache.iceberg.Table;
import org.apache.spark.SparkConf;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.classic.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.rules.TemporaryFolder;

import java.util.HashMap;

/** Iceberg read/write through the Spark 4.2 unified catalog. Paimon is not covered. */
public class TestSpark42IcebergCatalog {

  private static final String CATALOG = "unified_iceberg";
  private static final String DATABASE = "spark42_db";
  private static final String TABLE = "events";
  private static final String SESSION_DATABASE = "spark42_session_db";
  private static final String SESSION_TABLE = "session_events";

  private static TestAms ams;
  private static TemporaryFolder warehouse;
  private static SparkSession spark;

  @BeforeAll
  public static void start() throws Exception {
    ams = new TestAms();
    warehouse = new TemporaryFolder();
    ams.before();
    warehouse.create();

    CatalogMeta meta =
        new BasicCatalogTestHelper(TableFormat.ICEBERG)
            .buildCatalogMeta(warehouse.getRoot().getAbsolutePath());
    meta.setCatalogName("iceberg");
    meta.putToCatalogProperties(CatalogMetaProperties.TABLE_FORMATS, TableFormat.ICEBERG.name());
    ams.getAmsHandler().createCatalog(meta);

    String amsUri = ams.getServerUrl() + "/iceberg";
    SparkConf conf =
        new SparkConf()
            .setAppName("spark-4.2-iceberg-catalog")
            .setMaster("local[2]")
            .set(
                "spark.sql.extensions",
                "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions")
            .set("spark.sql.catalog." + CATALOG, "org.apache.amoro.spark.SparkUnifiedCatalog")
            .set("spark.sql.catalog." + CATALOG + ".ams.uri", amsUri)
            .set(
                "spark.sql.catalog.spark_catalog",
                "org.apache.amoro.spark.SparkUnifiedSessionCatalog")
            .set("spark.sql.catalog.spark_catalog.ams.uri", amsUri)
            .set("spark.sql.catalogImplementation", "in-memory")
            .set("spark.sql.warehouse.dir", warehouse.getRoot().getAbsolutePath())
            .set("spark.sql.sources.partitionOverwriteMode", "STATIC")
            .set("spark.ui.enabled", "false")
            .set("spark.driver.host", "localhost");
    spark = (SparkSession) SparkSession.builder().config(conf).getOrCreate();
    spark.sparkContext().setLogLevel("WARN");
  }

  @AfterAll
  public static void stop() {
    if (spark != null) {
      spark.stop();
      spark = null;
    }
    if (ams != null) {
      ams.after();
    }
  }

  @Test
  public void namedCatalogSupportsIcebergDmlMetadataAndProcedures() {
    sql("CREATE DATABASE " + CATALOG + "." + DATABASE);
    sql(
        "CREATE TABLE "
            + qualified(CATALOG, DATABASE, TABLE)
            + " (id int not null, data string, pt string) USING iceberg PARTITIONED BY (pt)");
    sql(
        "INSERT INTO "
            + qualified(CATALOG, DATABASE, TABLE)
            + " VALUES (1, 'a', '2020-01-01'), (2, 'b', '2020-01-02'), (3, 'c', '2020-01-03')");
    Assertions.assertEquals(
        3L, sql("SELECT * FROM " + qualified(CATALOG, DATABASE, TABLE)).count());

    sql(
        "INSERT OVERWRITE "
            + qualified(CATALOG, DATABASE, TABLE)
            + " VALUES (9, 'overwrite', '2020-02-01')");
    Dataset<Row> overwritten = sql("SELECT data FROM " + qualified(CATALOG, DATABASE, TABLE));
    Assertions.assertEquals(1L, overwritten.count());
    Assertions.assertEquals("overwrite", overwritten.head().getString(0));

    sql(
        "INSERT INTO "
            + qualified(CATALOG, DATABASE, TABLE)
            + " VALUES (1, 'a', '2020-01-01'), (2, 'b', '2020-01-02')");
    sql("UPDATE " + qualified(CATALOG, DATABASE, TABLE) + " SET data = 'updated' WHERE id = 1");
    Dataset<Row> updated =
        sql("SELECT data FROM " + qualified(CATALOG, DATABASE, TABLE) + " WHERE id = 1");
    Assertions.assertEquals("updated", updated.head().getString(0));

    sql("DELETE FROM " + qualified(CATALOG, DATABASE, TABLE) + " WHERE id = 2");
    Assertions.assertEquals(
        0L, sql("SELECT * FROM " + qualified(CATALOG, DATABASE, TABLE) + " WHERE id = 2").count());

    sql(
        "MERGE INTO "
            + qualified(CATALOG, DATABASE, TABLE)
            + " t USING (SELECT 1 AS id, 'merged' AS data, '2020-01-01' AS pt) s "
            + "ON t.id = s.id "
            + "WHEN MATCHED THEN UPDATE SET t.data = s.data "
            + "WHEN NOT MATCHED THEN INSERT *");
    Dataset<Row> merged =
        sql("SELECT data FROM " + qualified(CATALOG, DATABASE, TABLE) + " WHERE id = 1");
    Assertions.assertEquals("merged", merged.head().getString(0));

    sql("ALTER TABLE " + qualified(CATALOG, DATABASE, TABLE) + " ADD COLUMN extra string");
    Table iceberg = loadIcebergTable(DATABASE, TABLE);
    Assertions.assertTrue(iceberg.schema().findField("extra") != null);

    Assertions.assertTrue(
        sql("SELECT * FROM " + qualified(CATALOG, DATABASE, TABLE) + ".snapshots").columns().length
            > 0);

    Dataset<Row> procedure =
        sql("CALL " + CATALOG + ".system.remove_orphan_files('" + DATABASE + "." + TABLE + "')");
    Assertions.assertTrue(procedure.columns().length > 0);

    sql("DROP TABLE " + qualified(CATALOG, DATABASE, TABLE) + " PURGE");
    Assertions.assertFalse(amsCatalog().tableExists(DATABASE, TABLE));
  }

  @Test
  public void sessionCatalogSupportsIcebergCreateInsertAndMerge() {
    sql("CREATE DATABASE " + CATALOG + "." + SESSION_DATABASE);
    String sessionTable = qualified(CATALOG, SESSION_DATABASE, SESSION_TABLE);
    sql(
        "CREATE TABLE spark_catalog."
            + SESSION_DATABASE
            + "."
            + SESSION_TABLE
            + " (id int, data string, pt string) USING iceberg PARTITIONED BY (pt)");
    sql("INSERT INTO " + sessionTable + " VALUES (1, 'session', '2020-01-01')");
    Assertions.assertEquals(1L, sql("SELECT * FROM " + sessionTable).count());
    sql(
        "MERGE INTO "
            + sessionTable
            + " t USING (SELECT 1 AS id, 'session-merged' AS data, '2020-01-01' AS pt) s "
            + "ON t.id = s.id WHEN MATCHED THEN UPDATE SET t.data = s.data");
    Dataset<Row> merged = sql("SELECT data FROM " + sessionTable + " WHERE id = 1");
    Assertions.assertEquals("session-merged", merged.head().getString(0));
    sql("DROP TABLE " + sessionTable + " PURGE");
    Assertions.assertFalse(amsCatalog().tableExists(SESSION_DATABASE, SESSION_TABLE));
  }

  private static Dataset<Row> sql(String statement) {
    return spark.sql(statement);
  }

  private static String qualified(String catalog, String database, String table) {
    return catalog + "." + database + "." + table;
  }

  private static UnifiedCatalog amsCatalog() {
    return UnifiedCatalogLoader.loadUnifiedCatalog(ams.getServerUrl(), "iceberg", new HashMap<>());
  }

  private static Table loadIcebergTable(String database, String table) {
    return (Table) amsCatalog().loadTable(database, table).originalTable();
  }
}
