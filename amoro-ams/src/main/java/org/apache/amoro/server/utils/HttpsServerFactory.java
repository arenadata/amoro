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

package org.apache.amoro.server.utils;

import org.apache.amoro.config.ConfigOption;
import org.apache.amoro.config.Configurations;
import org.apache.amoro.server.AmoroManagementConf;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.security.alias.CredentialProviderFactory;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Builds a Jetty server with a TLS connector from the AMS http-server.ssl.* options. */
public class HttpsServerFactory {

  private static final Logger LOG = LoggerFactory.getLogger(HttpsServerFactory.class);

  public static Server createServer(Configurations serviceConfig) {
    int port = serviceConfig.getInteger(AmoroManagementConf.HTTP_SERVER_PORT);
    Server server = new Server();

    HttpConfiguration httpsConfig = new HttpConfiguration();
    httpsConfig.setSecureScheme("https");
    httpsConfig.setSecurePort(port);
    httpsConfig.addCustomizer(new SecureRequestCustomizer(false));

    ServerConnector connector =
        new ServerConnector(
            server,
            new SslConnectionFactory(
                createSslContextFactory(serviceConfig), HttpVersion.HTTP_1_1.asString()),
            new HttpConnectionFactory(httpsConfig));
    connector.setPort(port);
    server.addConnector(connector);
    return server;
  }

  public static SslContextFactory.Server createSslContextFactory(Configurations serviceConfig) {
    String keystorePath =
        serviceConfig.getString(AmoroManagementConf.HTTP_SERVER_SSL_KEYSTORE_PATH);
    if (StringUtils.isBlank(keystorePath)) {
      throw new IllegalArgumentException(
          String.format(
              "%s must be set when %s is true",
              AmoroManagementConf.HTTP_SERVER_SSL_KEYSTORE_PATH.key(),
              AmoroManagementConf.HTTP_SERVER_SSL_ENABLED.key()));
    }

    String keystoreType =
        serviceConfig.getString(AmoroManagementConf.HTTP_SERVER_SSL_KEYSTORE_TYPE);
    Configuration credentialProviderConf = buildCredentialProviderConf(serviceConfig);
    String keystorePassword =
        resolvePassword(
            serviceConfig,
            credentialProviderConf,
            AmoroManagementConf.HTTP_SERVER_SSL_KEYSTORE_PASSWORD);
    validateKeystore(keystorePath, keystoreType, keystorePassword);

    SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
    sslContextFactory.setKeyStorePath(keystorePath);
    sslContextFactory.setKeyStoreType(keystoreType);
    if (keystorePassword != null) {
      sslContextFactory.setKeyStorePassword(keystorePassword);
    }
    String keyPassword =
        resolvePassword(
            serviceConfig,
            credentialProviderConf,
            AmoroManagementConf.HTTP_SERVER_SSL_KEY_PASSWORD);
    if (StringUtils.isNotBlank(keyPassword)) {
      sslContextFactory.setKeyManagerPassword(keyPassword);
    }

    sslContextFactory.setIncludeProtocols(
        resolveProtocols(
            serviceConfig.getString(AmoroManagementConf.HTTP_SERVER_SSL_MIN_TLS_VERSION)));

    String cipherSuites =
        serviceConfig.getString(AmoroManagementConf.HTTP_SERVER_SSL_CIPHER_SUITES);
    if (StringUtils.isNotBlank(cipherSuites)) {
      String[] suites =
          Arrays.stream(cipherSuites.split(","))
              .map(String::trim)
              .filter(StringUtils::isNotEmpty)
              .toArray(String[]::new);
      warnOnUnknownCipherSuites(suites);
      sslContextFactory.setIncludeCipherSuites(suites);
    }
    return sslContextFactory;
  }

  /**
   * Builds the Hadoop configuration used to resolve the credential provider, or null when no
   * credential-provider option is set. Applied in order, later entries overriding earlier ones:
   * core-site file, http-server.ssl.credential-provider.conf.* entries, the explicit provider path.
   */
  private static Configuration buildCredentialProviderConf(Configurations serviceConfig) {
    String providerPath =
        serviceConfig.getString(AmoroManagementConf.HTTP_SERVER_SSL_CREDENTIAL_PROVIDER_PATH);
    String coreSitePath =
        serviceConfig.getString(AmoroManagementConf.HTTP_SERVER_SSL_CREDENTIAL_PROVIDER_CORE_SITE);
    String confPrefix = AmoroManagementConf.HTTP_SERVER_SSL_CREDENTIAL_PROVIDER_CONF_PREFIX;
    Map<String, String> confOverrides = new HashMap<>();
    serviceConfig
        .toMap()
        .forEach(
            (key, value) -> {
              if (key.startsWith(confPrefix) && key.length() > confPrefix.length()) {
                confOverrides.put(key.substring(confPrefix.length()), value);
              }
            });
    if (StringUtils.isBlank(providerPath)
        && StringUtils.isBlank(coreSitePath)
        && confOverrides.isEmpty()) {
      return null;
    }

    Configuration hadoopConf = new Configuration();
    if (StringUtils.isNotBlank(coreSitePath)) {
      if (!Files.isReadable(Paths.get(coreSitePath))) {
        throw new IllegalArgumentException(
            String.format(
                "File %s configured by %s does not exist or is not readable",
                coreSitePath,
                AmoroManagementConf.HTTP_SERVER_SSL_CREDENTIAL_PROVIDER_CORE_SITE.key()));
      }
      hadoopConf.addResource(new Path(coreSitePath));
    }
    confOverrides.forEach(hadoopConf::set);
    if (StringUtils.isNotBlank(providerPath)) {
      hadoopConf.set(CredentialProviderFactory.CREDENTIAL_PROVIDER_PATH, providerPath);
    }
    return hadoopConf;
  }

  /**
   * Resolves a password from the Hadoop credential provider, using the option key as the alias.
   * Falls back to the plain-text option value.
   */
  private static String resolvePassword(
      Configurations serviceConfig,
      Configuration credentialProviderConf,
      ConfigOption<String> passwordOption) {
    if (credentialProviderConf != null) {
      try {
        char[] password = credentialProviderConf.getPassword(passwordOption.key());
        if (password != null) {
          return new String(password);
        }
      } catch (IOException e) {
        throw new IllegalArgumentException(
            String.format(
                "Failed to resolve alias %s from credential provider %s",
                passwordOption.key(),
                credentialProviderConf.get(
                    CredentialProviderFactory.CREDENTIAL_PROVIDER_PATH, "<not set>")),
            e);
      }
    }
    return serviceConfig.getString(passwordOption);
  }

  /** Loads the keystore eagerly so misconfiguration fails at startup with a clear message. */
  private static void validateKeystore(
      String keystorePath, String keystoreType, String keystorePassword) {
    if (!Files.isReadable(Paths.get(keystorePath))) {
      throw new IllegalArgumentException(
          String.format(
              "Keystore file %s configured by %s does not exist or is not readable",
              keystorePath, AmoroManagementConf.HTTP_SERVER_SSL_KEYSTORE_PATH.key()));
    }
    try (InputStream in = Files.newInputStream(Paths.get(keystorePath))) {
      KeyStore keyStore = KeyStore.getInstance(keystoreType);
      keyStore.load(in, keystorePassword == null ? null : keystorePassword.toCharArray());
    } catch (KeyStoreException e) {
      throw new IllegalArgumentException(
          String.format(
              "Unsupported keystore type %s configured by %s",
              keystoreType, AmoroManagementConf.HTTP_SERVER_SSL_KEYSTORE_TYPE.key()),
          e);
    } catch (GeneralSecurityException | IOException e) {
      throw new IllegalArgumentException(
          String.format(
              "Failed to load keystore %s, check %s and %s: %s",
              keystorePath,
              AmoroManagementConf.HTTP_SERVER_SSL_KEYSTORE_PASSWORD.key(),
              AmoroManagementConf.HTTP_SERVER_SSL_KEYSTORE_TYPE.key(),
              e.getMessage()),
          e);
    }
  }

  /** Include patterns may be regexes, so unknown names are only reported, not rejected. */
  private static void warnOnUnknownCipherSuites(String[] suites) {
    try {
      Set<String> supported =
          new HashSet<>(
              Arrays.asList(SSLContext.getDefault().getSupportedSSLParameters().getCipherSuites()));
      for (String suite : suites) {
        if (!supported.contains(suite)) {
          LOG.warn(
              "Cipher suite {} is not supported by the JVM, it will only match as a regex pattern",
              suite);
        }
      }
    } catch (NoSuchAlgorithmException e) {
      LOG.warn("Failed to list JVM cipher suites", e);
    }
  }

  public static String[] resolveProtocols(String minTlsVersion) {
    switch (minTlsVersion) {
      case "TLSv1.2":
        return new String[] {"TLSv1.2", "TLSv1.3"};
      case "TLSv1.3":
        return new String[] {"TLSv1.3"};
      default:
        throw new IllegalArgumentException(
            String.format(
                "Unsupported %s: %s, expected TLSv1.2 or TLSv1.3",
                AmoroManagementConf.HTTP_SERVER_SSL_MIN_TLS_VERSION.key(), minTlsVersion));
    }
  }
}
