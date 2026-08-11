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

import org.apache.amoro.TableFormat;
import org.apache.amoro.api.CatalogMeta;
import org.apache.amoro.exception.AlreadyExistsException;
import org.apache.amoro.exception.CatalogCreationException;
import org.apache.amoro.properties.CatalogMetaProperties;
import org.apache.amoro.server.dashboard.PlatformFileManager;
import org.apache.amoro.server.dashboard.controller.CatalogController;
import org.apache.amoro.server.dashboard.model.CatalogRegisterInfo;
import org.apache.commons.lang3.StringUtils;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Creates catalogs declared in Environments.getConfDir()/catalogs.yaml when AMS starts. The file is
 * optional. Each entry is validated and materialized through the same code path as the dashboard
 * "create catalog" API. Catalogs that already exist are skipped.
 */
public class CatalogInitializer {

  private static final Logger LOG = LoggerFactory.getLogger(CatalogInitializer.class);

  public static final String CATALOGS_CONFIG_FILENAME = "catalogs.yaml";

  private static final Set<String> SUPPORTED_TYPES =
      Set.of(
          CatalogMetaProperties.CATALOG_TYPE_AMS,
          CatalogMetaProperties.CATALOG_TYPE_HIVE,
          CatalogMetaProperties.CATALOG_TYPE_HADOOP,
          CatalogMetaProperties.CATALOG_TYPE_FILESYSTEM,
          CatalogMetaProperties.CATALOG_TYPE_GLUE,
          CatalogMetaProperties.CATALOG_TYPE_CUSTOM,
          CatalogMetaProperties.CATALOG_TYPE_REST);

  /** Storage config keys whose values are config files. */
  private static final List<String> STORAGE_FILE_KEYS =
      Arrays.asList(
          CatalogMetaProperties.STORAGE_CONFIGS_KEY_CORE_SITE,
          CatalogMetaProperties.STORAGE_CONFIGS_KEY_HDFS_SITE,
          CatalogMetaProperties.STORAGE_CONFIGS_KEY_HIVE_SITE);

  /** Auth config keys whose values are config files. */
  private static final List<String> AUTH_FILE_KEYS =
      Arrays.asList(
          CatalogMetaProperties.AUTH_CONFIGS_KEY_KEYTAB,
          CatalogMetaProperties.AUTH_CONFIGS_KEY_KRB5);

  private final CatalogManager catalogManager;
  private final PlatformFileManager platformFileManager;
  private final CatalogController catalogController;
  private final Path catalogFile;

  public CatalogInitializer(CatalogManager catalogManager, String configDir) {
    this.catalogManager = catalogManager;
    this.platformFileManager = new PlatformFileManager();
    this.catalogController = new CatalogController(catalogManager, platformFileManager);
    this.catalogFile = Paths.get(configDir).resolve(CATALOGS_CONFIG_FILENAME);
  }

  public void initialize() {
    if (!Files.exists(catalogFile)) {
      LOG.info("Not found catalog config {}, skip catalog initialization.", catalogFile);
      return;
    }
    LOG.info("Initializing catalogs from {}", catalogFile);
    List<CatalogRegisterInfo> catalogs = parseCatalogs();
    try {
      validateAll(catalogs);
    } catch (CatalogCreationException e) {
      LOG.error("Catalog config validation failed", e);
      return;
    }
    for (CatalogRegisterInfo info : catalogs) {
      if (catalogManager.catalogExist(info.getName())) {
        LOG.info("Catalog {} already exists, skip creating it from config.", info.getName());
        continue;
      }
      try {
        encodeConfigFiles(info);
        CatalogMeta catalogMeta = catalogController.buildCatalogMeta(info);
        catalogManager.createCatalog(catalogMeta);
      } catch (AlreadyExistsException e) {
        LOG.info("Catalog {} was already created", info.getName());
      } catch (Throwable t) {
        LOG.error("Failed to create catalog {}", info.getName(), t);
      }
    }
  }

  /**
   * Read each file-valued option from disk and stage it through {@link PlatformFileManager},
   * replacing the path in the config map with the resulting file id.
   */
  private void encodeConfigFiles(CatalogRegisterInfo info) {
    encodeFiles(info.getStorageConfig(), STORAGE_FILE_KEYS);
    encodeFiles(info.getAuthConfig(), AUTH_FILE_KEYS);
  }

  private void validateConfigs(Map<String, String> config, List<String> fileKeys) {
    if (config == null) {
      return;
    }
    // check that all paths are exist to not create orphan rows with encoded config content
    for (String key : fileKeys) {
      String path = config.get(key);
      if (StringUtils.isNotEmpty(path)) {
        if (!Paths.get(path).toFile().exists()) {
          throw new CatalogCreationException("Config file %s does not exist.".formatted(path));
        }
      }
    }
  }

  private void encodeFiles(Map<String, String> config, List<String> fileKeys) {
    if (config == null) {
      return;
    }
    for (String key : fileKeys) {
      String pathValue = config.get(key);
      if (StringUtils.isEmpty(pathValue)) {
        continue;
      }
      Path filePath = Paths.get(pathValue);
      try {
        String contentB64 = Base64.getEncoder().encodeToString(Files.readAllBytes(filePath));
        Integer fileId = platformFileManager.addFile(filePath.getFileName().toString(), contentB64);
        config.put(key, String.valueOf(fileId));
      } catch (IOException e) {
        throw new CatalogCreationException("Failed to read config file %s".formatted(filePath), e);
      }
    }
  }

  private List<CatalogRegisterInfo> parseCatalogs() {
    try (InputStream in = Files.newInputStream(catalogFile)) {
      CatalogsConfig config = new YAMLMapper().readValue(in, CatalogsConfig.class);
      if (config == null || config.catalogs() == null) {
        return Collections.emptyList();
      }
      return config.catalogs().stream().map(this::normalize).collect(Collectors.toList());
    } catch (IOException e) {
      LOG.error("Cannot parse catalog config file {}. Catalog creation skipped.", catalogFile, e);
    }
    return Collections.emptyList();
  }

  private CatalogRegisterInfo normalize(CatalogRegisterInfo info) {
    if (info.getTableProperties() == null) {
      info.setTableProperties(new HashMap<>());
    }
    if (info.getProperties() == null) {
      info.setProperties(new HashMap<>());
    }
    if (info.getAuthConfig() == null) {
      info.setAuthConfig(new HashMap<>());
    }
    if (info.getStorageConfig() == null) {
      info.setStorageConfig(new HashMap<>());
    }
    return info;
  }

  /**
   * Validate the whole configuration before catalog creation. Combine all found errors into the
   * single message.
   */
  private void validateAll(List<CatalogRegisterInfo> catalogs) {
    List<String> errors = new ArrayList<>();
    Set<String> catalogNames = new HashSet<>();

    for (CatalogRegisterInfo info : catalogs) {
      String name = info.getName();
      if (StringUtils.isEmpty(name)) {
        errors.add("a catalog entry is missing the required 'name'");
        continue;
      }
      if (!catalogNames.add(name)) {
        errors.add("duplicate catalog name '%s'".formatted(name));
      }

      boolean isValid = true;
      if (!SUPPORTED_TYPES.contains(info.getType())) {
        errors.add(
            "catalog '%s': unknown type '%s'. Supported types: %s"
                .formatted(name, info.getType(), SUPPORTED_TYPES));
        isValid = false;
      }

      List<String> formats = info.getTableFormatList();
      if (formats == null || formats.isEmpty()) {
        errors.add("catalog '%s': 'tableFormatList' must not be empty".formatted(name));
        isValid = false;
      } else {
        for (String format : formats) {
          if (!isSupportedFormat(format)) {
            errors.add(
                "catalog '%s': unknown table format '%s'. Supported formats: %s"
                    .formatted(name, format, supportedFormats()));
            isValid = false;
          }
        }
      }
      try {
        validateConfigs(info.getStorageConfig(), STORAGE_FILE_KEYS);
        validateConfigs(info.getAuthConfig(), AUTH_FILE_KEYS);
      } catch (CatalogCreationException e) {
        errors.add("catalog '%s': %s".formatted(name, e.getMessage()));
        isValid = false;
      }

      if (isValid) {
        try {
          catalogController.validateCatalogRegisterInfo(info);
        } catch (Exception e) {
          errors.add("catalog '%s': %s".formatted(name, e.getMessage()));
        }
      }
    }

    if (!errors.isEmpty()) {
      throw new CatalogCreationException(
          "Invalid %s, %d problem(s):%n  - %s"
              .formatted(
                  CATALOGS_CONFIG_FILENAME,
                  errors.size(),
                  String.join("%n  - ".formatted(), errors)));
    }
  }

  private boolean isSupportedFormat(String format) {
    return TableFormat.valueOf(format) != null;
  }

  private String supportedFormats() {
    return Arrays.stream(TableFormat.values())
        .map(TableFormat::name)
        .collect(Collectors.joining(", "));
  }

  public record CatalogsConfig(List<CatalogRegisterInfo> catalogs) {}
}
