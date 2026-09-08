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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.apache.amoro.TableFormat;
import org.apache.amoro.api.CatalogMeta;
import org.apache.amoro.catalog.CatalogTestHelpers;
import org.apache.amoro.properties.CatalogMetaProperties;
import org.apache.amoro.server.AMSManagerTestBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TestCatalogInitializer extends AMSManagerTestBase {

  @Rule public TemporaryFolder temp = new TemporaryFolder();

  private Path confDir;
  private final List<String> createdCatalogs = new ArrayList<>();

  private CatalogInitializer catalogInitializer;

  @Before
  public void setUp() throws IOException {
    this.confDir = temp.newFolder("conf").toPath();
    this.catalogInitializer = new CatalogInitializer(CATALOG_MANAGER, confDir.toString());
  }

  @After
  public void tearDown() {
    for (String name : createdCatalogs) {
      try {
        CATALOG_MANAGER.dropCatalog(name);
      } catch (Exception ignored) {
      }
    }
    createdCatalogs.clear();
  }

  @Test
  public void testCreatesHadoopCatalogWithoutConfigFiles() throws IOException {
    String name = "init_hadoop_no_files";
    createdCatalogs.add(name);
    run(hadoopCatalog(name, newWarehouse()));

    assertTrue(CATALOG_MANAGER.catalogExist(name));
    CatalogMeta meta = CATALOG_MANAGER.getCatalogMeta(name);
    assertEquals(CatalogMetaProperties.CATALOG_TYPE_FILESYSTEM, meta.getCatalogType());
    // No site file supplied should be empty config
    assertEquals(
        emptyXmlBase64(),
        meta.getStorageConfigs().get(CatalogMetaProperties.STORAGE_CONFIGS_KEY_CORE_SITE));
  }

  @Test
  public void testStagesConfigFileContentFromDisk() throws IOException {
    String name = "init_hadoop_with_files";
    createdCatalogs.add(name);

    String coreSiteXml =
        "<configuration><property><name>k</name><value>v</value></property></configuration>";
    File coreSite = temp.newFile("core-site.xml");
    Files.write(coreSite.toPath(), coreSiteXml.getBytes(StandardCharsets.UTF_8));

    String yaml =
        """
        catalogs:
          - name: %s
            type: hadoop
            optimizerGroup: local
            tableFormatList:
              - ICEBERG
            storageConfig:
              storage.type: Hadoop
              hadoop.core.site: %s
            authConfig:
              auth.type: simple
              auth.simple.hadoop_username: test
            properties:
              warehouse: %s
            tableProperties: {}
        """
            .formatted(name, coreSite.getAbsolutePath(), newWarehouse());
    run(yaml);

    assertTrue(CATALOG_MANAGER.catalogExist(name));
    String expected =
        Base64.getEncoder().encodeToString(coreSiteXml.getBytes(StandardCharsets.UTF_8));
    assertEquals(
        "core-site.xml content should be read from disk and stored as base64",
        expected,
        CATALOG_MANAGER
            .getCatalogMeta(name)
            .getStorageConfigs()
            .get(CatalogMetaProperties.STORAGE_CONFIGS_KEY_CORE_SITE));
  }

  @Test
  public void testMultipleValidCatalogsAllCreated() throws IOException {
    String a = "init_multi_a";
    String b = "init_multi_b";
    createdCatalogs.add(a);
    createdCatalogs.add(b);
    String yaml =
        """
        catalogs:
          - name: %s
            type: hadoop
            optimizerGroup: local
            tableFormatList:
              - ICEBERG
            storageConfig:
              storage.type: Hadoop
            authConfig:
              auth.type: simple
              auth.simple.hadoop_username: test
            properties:
              warehouse: %s
            tableProperties: {}
          - name: %s
            type: hadoop
            optimizerGroup: local
            tableFormatList:
              - ICEBERG
            storageConfig:
              storage.type: Hadoop
            authConfig:
              auth.type: simple
              auth.simple.hadoop_username: test
            properties:
              warehouse: %s
            tableProperties: {}
        """
            .formatted(a, newWarehouse(), b, newWarehouse());
    run(yaml);

    assertTrue(CATALOG_MANAGER.catalogExist(a));
    assertTrue(CATALOG_MANAGER.catalogExist(b));
  }

  @Test
  public void testOmittedTablePropertiesIsDefaultedAndCreated() throws IOException {
    String name = "init_no_table_props";
    createdCatalogs.add(name);
    String yaml =
        """
        catalogs:
          - name: %s
            type: hadoop
            optimizerGroup: local
            tableFormatList:
              - ICEBERG
            storageConfig:
              storage.type: Hadoop
            authConfig:
              auth.type: simple
              auth.simple.hadoop_username: test
            properties:
              warehouse: %s
        """
            .formatted(name, newWarehouse());
    run(yaml);

    assertTrue(CATALOG_MANAGER.catalogExist(name));
  }

  @Test
  public void testInvalidAuthTypeIsRejected() throws IOException {
    String name = "init_bad_auth";
    String yaml =
        """
        catalogs:
          - name: %s
            type: hadoop
            optimizerGroup: local
            tableFormatList:
              - ICEBERG
            storageConfig:
              storage.type: Hadoop
            authConfig:
              auth.type: Kerber
            properties:
              warehouse: %s
            tableProperties: {}
        """
            .formatted(name, newWarehouse());

    run(yaml);
    assertFalse(
        "a catalog with an unsupported auth type must not be created",
        CATALOG_MANAGER.catalogExist(name));
  }

  @Test
  public void testMissingConfigFileIsNoOp() {
    long before = CATALOG_MANAGER.listCatalogMetas().size();
    catalogInitializer.initialize();
    assertEquals(before, CATALOG_MANAGER.listCatalogMetas().size());
  }

  @Test
  public void testEmptyCatalogsListIsNoOp() throws IOException {
    long before = CATALOG_MANAGER.listCatalogMetas().size();
    run("catalogs:\n");
    assertEquals(before, CATALOG_MANAGER.listCatalogMetas().size());
  }

  @Test
  public void testMalformedYamlDoesNotThrow() throws IOException {
    long before = CATALOG_MANAGER.listCatalogMetas().size();
    run("catalogs: [ this is : not valid yaml");
    assertEquals(before, CATALOG_MANAGER.listCatalogMetas().size());
  }

  @Test
  public void testExistingCatalogIsSkippedAndUnchanged() throws IOException {
    String name = "init_existing";
    createdCatalogs.add(name);

    String originalWarehouse = newWarehouse();
    Map<String, String> props = new HashMap<>();
    props.put(CatalogMetaProperties.KEY_WAREHOUSE, originalWarehouse);
    CatalogMeta existing =
        CatalogTestHelpers.buildCatalogMeta(
            name, CatalogMetaProperties.CATALOG_TYPE_FILESYSTEM, props, TableFormat.ICEBERG);
    CATALOG_MANAGER.createCatalog(existing);

    // Same name but a different warehouse
    run(hadoopCatalog(name, newWarehouse()));

    assertEquals(
        "existing catalog must not be overwritten",
        originalWarehouse,
        CATALOG_MANAGER
            .getCatalogMeta(name)
            .getCatalogProperties()
            .get(CatalogMetaProperties.KEY_WAREHOUSE));
  }

  @Test
  public void testMissingConfigFilePathSkipsCatalog() throws IOException {
    String name = "init_missing_file";
    String missingPath = new File(temp.getRoot(), "does-not-exist.xml").getAbsolutePath();
    String yaml =
        """
        catalogs:
          - name: %s
            type: hadoop
            optimizerGroup: local
            tableFormatList:
              - ICEBERG
            storageConfig:
              storage.type: Hadoop
              hadoop.core.site: %s
            authConfig:
              auth.type: simple
              auth.simple.hadoop_username: test
            properties:
              warehouse: %s
            tableProperties: {}
        """
            .formatted(name, missingPath, newWarehouse());
    run(yaml);

    assertFalse(
        "a catalog referencing a missing config file must not be created",
        CATALOG_MANAGER.catalogExist(name));
  }

  @Test
  public void testMissingAuthFilePathSkipsCatalog() throws IOException {
    String name = "init_missing_keytab";
    String missingKeytab = new File(temp.getRoot(), "missing.keytab").getAbsolutePath();
    String yaml =
        """
        catalogs:
          - name: %s
            type: hadoop
            optimizerGroup: local
            tableFormatList:
              - ICEBERG
            storageConfig:
              storage.type: Hadoop
            authConfig:
              auth.type: simple
              auth.simple.hadoop_username: test
              %s: %s
            properties:
              warehouse: %s
            tableProperties: {}
        """
            .formatted(
                name, CatalogMetaProperties.AUTH_CONFIGS_KEY_KEYTAB, missingKeytab, newWarehouse());
    run(yaml);

    assertFalse(
        "a catalog referencing a missing keytab must not be created",
        CATALOG_MANAGER.catalogExist(name));
  }

  @Test
  public void testUnreadableConfigFileSkipsCatalog() throws IOException {
    String name = "init_dir_as_file";
    // A directory exists() but cannot be read as a file
    File dir = temp.newFolder("core-site-dir");
    String yaml =
        """
        catalogs:
          - name: %s
            type: hadoop
            optimizerGroup: local
            tableFormatList:
              - ICEBERG
            storageConfig:
              storage.type: Hadoop
              hadoop.core.site: %s
            authConfig:
              auth.type: simple
              auth.simple.hadoop_username: test
            properties:
              warehouse: %s
            tableProperties: {}
        """
            .formatted(name, dir.getAbsolutePath(), newWarehouse());
    run(yaml);

    assertFalse(
        "a catalog whose config file cannot be read must not be created",
        CATALOG_MANAGER.catalogExist(name));
  }

  @Test
  public void testInvalidCatalogSkippedAndNothingCreated() throws IOException {
    String invalid = "init_invalid_no_warehouse";
    String valid = "init_valid_after_invalid";

    String yaml =
        """
        catalogs:
          - name: %s
            type: hadoop
            optimizerGroup: local
            tableFormatList:
              - ICEBERG
            storageConfig:
              storage.type: Hadoop
            authConfig:
              auth.type: simple
              auth.simple.hadoop_username: test
            properties: {}
            tableProperties: {}
          - name: %s
            type: hadoop
            optimizerGroup: local
            tableFormatList:
              - ICEBERG
            storageConfig:
              storage.type: Hadoop
            authConfig:
              auth.type: simple
              auth.simple.hadoop_username: test
            properties:
              warehouse: %s
            tableProperties: {}
        """
            .formatted(invalid, valid, newWarehouse());
    run(yaml);

    assertEquals(0, CATALOG_MANAGER.listCatalogMetas().size());
  }

  @Test
  public void testDuplicateKeyConfigCreatesNothing() throws IOException {
    String yaml =
        """
        catalogs:
          - name: dup_catalog
            type: hadoop
            type: hive
            optimizerGroup: local
            tableFormatList:
              - ICEBERG
            storageConfig:
              storage.type: Hadoop
            authConfig:
              auth.type: simple
              auth.simple.hadoop_username: test
            properties:
              warehouse: %s
            tableProperties: {}
        """
            .formatted(newWarehouse());
    run(yaml);
    assertEquals(0, CATALOG_MANAGER.listCatalogMetas().size());
    assertFalse(CATALOG_MANAGER.catalogExist("dup_catalog"));
  }

  private void run(String yaml) throws IOException {
    Files.writeString(confDir.resolve(CatalogInitializer.CATALOGS_CONFIG_FILENAME), yaml);
    catalogInitializer.initialize();
  }

  private String newWarehouse() throws IOException {
    return temp.newFolder().getAbsolutePath();
  }

  private String hadoopCatalog(String name, String warehouse) {
    return """
      catalogs:
        - name: %s
          type: hadoop
          optimizerGroup: local
          tableFormatList:
            - ICEBERG
          storageConfig:
            storage.type: Hadoop
          authConfig:
            auth.type: simple
            auth.simple.hadoop_username: test
          properties:
            warehouse: %s
          tableProperties: {}
      """
        .formatted(name, warehouse);
  }

  private static String emptyXmlBase64() {
    return Base64.getEncoder()
        .encodeToString("<configuration></configuration>".getBytes(StandardCharsets.UTF_8));
  }
}
