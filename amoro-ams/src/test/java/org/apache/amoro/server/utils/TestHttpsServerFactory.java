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

import io.javalin.Javalin;
import org.apache.amoro.config.Configurations;
import org.apache.amoro.server.AmoroManagementConf;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.cert.X509Certificate;

public class TestHttpsServerFactory {

  private static final String KEYSTORE_PASSWORD = "test-password";

  @TempDir static Path tempDir;

  private static String keystorePath;
  private static Server server;
  private static int serverPort;

  @BeforeAll
  static void generateKeystoreAndStartServer() throws Exception {
    keystorePath = tempDir.resolve("keystore.p12").toString();
    String keytool = System.getProperty("java.home") + "/bin/keytool";
    Process process =
        new ProcessBuilder(
                keytool,
                "-genkeypair",
                "-alias",
                "ams",
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-validity",
                "1",
                "-dname",
                "CN=localhost",
                "-storetype",
                "PKCS12",
                "-keystore",
                keystorePath,
                "-storepass",
                KEYSTORE_PASSWORD)
            .redirectErrorStream(true)
            .start();
    Assertions.assertEquals(0, process.waitFor(), "keytool failed to generate a test keystore");

    Configurations conf = sslConfigurations();
    conf.setInteger(AmoroManagementConf.HTTP_SERVER_PORT, 0);
    server = HttpsServerFactory.createServer(conf);
    server.start();
    serverPort = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
  }

  @AfterAll
  static void stopServer() throws Exception {
    if (server != null) {
      server.stop();
    }
  }

  private static Configurations sslConfigurations() {
    Configurations conf = new Configurations();
    conf.setBoolean(AmoroManagementConf.HTTP_SERVER_SSL_ENABLED, true);
    conf.setString(AmoroManagementConf.HTTP_SERVER_SSL_KEYSTORE_PATH, keystorePath);
    conf.setString(AmoroManagementConf.HTTP_SERVER_SSL_KEYSTORE_PASSWORD, KEYSTORE_PASSWORD);
    return conf;
  }

  private static SSLContext trustAllContext() throws Exception {
    SSLContext sslContext = SSLContext.getInstance("TLS");
    TrustManager trustAll =
        new X509TrustManager() {
          @Override
          public void checkClientTrusted(X509Certificate[] chain, String authType) {}

          @Override
          public void checkServerTrusted(X509Certificate[] chain, String authType) {}

          @Override
          public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
          }
        };
    sslContext.init(null, new TrustManager[] {trustAll}, null);
    return sslContext;
  }

  private static SSLSocket openClientSocket(String... protocols) throws Exception {
    SSLSocket socket =
        (SSLSocket) trustAllContext().getSocketFactory().createSocket("localhost", serverPort);
    socket.setEnabledProtocols(protocols);
    return socket;
  }

  @Test
  void testTls12HandshakeSucceeds() throws Exception {
    try (SSLSocket socket = openClientSocket("TLSv1.2")) {
      socket.startHandshake();
      Assertions.assertEquals("TLSv1.2", socket.getSession().getProtocol());
    }
  }

  @Test
  void testTls13HandshakeSucceeds() throws Exception {
    try (SSLSocket socket = openClientSocket("TLSv1.3")) {
      socket.startHandshake();
      Assertions.assertEquals("TLSv1.3", socket.getSession().getProtocol());
    }
  }

  @Test
  void testMinTlsVersion13RejectsTls12() throws Exception {
    Configurations conf = sslConfigurations();
    conf.setInteger(AmoroManagementConf.HTTP_SERVER_PORT, 0);
    conf.setString(AmoroManagementConf.HTTP_SERVER_SSL_MIN_TLS_VERSION, "TLSv1.3");
    Server tls13Server = HttpsServerFactory.createServer(conf);
    tls13Server.start();
    int tls13Port = ((ServerConnector) tls13Server.getConnectors()[0]).getLocalPort();
    try {
      SSLContext sslContext = SSLContext.getInstance("TLS");
      sslContext.init(null, null, null);
      try (SSLSocket socket =
          (SSLSocket) sslContext.getSocketFactory().createSocket("localhost", tls13Port)) {
        socket.setEnabledProtocols(new String[] {"TLSv1.2"});
        Assertions.assertThrows(IOException.class, socket::startHandshake);
      }
    } finally {
      tls13Server.stop();
    }
  }

  @Test
  void testCipherSuitesAreApplied() throws Exception {
    Configurations conf = sslConfigurations();
    conf.setString(
        AmoroManagementConf.HTTP_SERVER_SSL_CIPHER_SUITES,
        "TLS_AES_256_GCM_SHA384, TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384");
    Assertions.assertArrayEquals(
        new String[] {"TLS_AES_256_GCM_SHA384", "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"},
        HttpsServerFactory.createSslContextFactory(conf).getIncludeCipherSuites());
  }

  @Test
  void testResolveProtocols() {
    Assertions.assertArrayEquals(
        new String[] {"TLSv1.2", "TLSv1.3"}, HttpsServerFactory.resolveProtocols("TLSv1.2"));
    Assertions.assertArrayEquals(
        new String[] {"TLSv1.3"}, HttpsServerFactory.resolveProtocols("TLSv1.3"));
    Assertions.assertThrows(
        IllegalArgumentException.class, () -> HttpsServerFactory.resolveProtocols("TLSv1.1"));
  }

  @Test
  void testMissingKeystorePathFails() {
    Configurations conf = new Configurations();
    conf.setBoolean(AmoroManagementConf.HTTP_SERVER_SSL_ENABLED, true);
    Assertions.assertThrows(
        IllegalArgumentException.class, () -> HttpsServerFactory.createSslContextFactory(conf));
  }

  @Test
  void testMissingKeystoreFileFails() {
    Configurations conf = sslConfigurations();
    conf.setString(
        AmoroManagementConf.HTTP_SERVER_SSL_KEYSTORE_PATH,
        tempDir.resolve("absent.p12").toString());
    IllegalArgumentException exception =
        Assertions.assertThrows(
            IllegalArgumentException.class, () -> HttpsServerFactory.createSslContextFactory(conf));
    Assertions.assertTrue(exception.getMessage().contains("does not exist"));
  }

  @Test
  void testWrongKeystorePasswordFails() {
    Configurations conf = sslConfigurations();
    conf.setString(AmoroManagementConf.HTTP_SERVER_SSL_KEYSTORE_PASSWORD, "wrong-password");
    IllegalArgumentException exception =
        Assertions.assertThrows(
            IllegalArgumentException.class, () -> HttpsServerFactory.createSslContextFactory(conf));
    Assertions.assertTrue(exception.getMessage().contains("Failed to load keystore"));
  }

  @Test
  void testInvalidKeystoreTypeFails() {
    Configurations conf = sslConfigurations();
    conf.setString(AmoroManagementConf.HTTP_SERVER_SSL_KEYSTORE_TYPE, "UNKNOWN");
    IllegalArgumentException exception =
        Assertions.assertThrows(
            IllegalArgumentException.class, () -> HttpsServerFactory.createSslContextFactory(conf));
    Assertions.assertTrue(exception.getMessage().contains("Unsupported keystore type"));
  }

  @Test
  void testJavalinServesHttpsOnCustomServer() throws Exception {
    Configurations conf = sslConfigurations();
    conf.setInteger(AmoroManagementConf.HTTP_SERVER_PORT, 0);
    Javalin app =
        Javalin.create(
            config -> {
              config.showJavalinBanner = false;
              config.server(() -> HttpsServerFactory.createServer(conf));
            });
    app.get("/ping", ctx -> ctx.result("pong"));
    app.start();
    try {
      int port = app.port();
      HttpsURLConnection connection =
          (HttpsURLConnection) new URL("https://localhost:" + port + "/ping").openConnection();
      connection.setSSLSocketFactory(trustAllContext().getSocketFactory());
      connection.setHostnameVerifier((hostname, session) -> true);
      Assertions.assertEquals(200, connection.getResponseCode());
      Assertions.assertEquals(
          "pong", new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    } finally {
      app.stop();
    }
  }
}
