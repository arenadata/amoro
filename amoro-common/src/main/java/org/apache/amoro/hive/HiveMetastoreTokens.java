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

package org.apache.amoro.hive;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.TokenIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Makes a Hive metastore delegation token selectable by {@code HiveMetaStoreClient}.
 *
 * <p>Hive looks up a token whose service equals {@code metastore.token.signature}. That property
 * defaults to an empty string, while {@code getDelegationToken} returns a token whose service is
 * the metastore principal. Without a token whose service is empty, the client ignores it and falls
 * back to Kerberos.
 *
 * <p>The empty string is also the credentials key of the YARN AM-RM token. Spark merges delegation
 * tokens with {@code Credentials.addAll}, which overwrites by key, so this class stores the
 * empty-service Hive token under {@link #CREDENTIALS_ALIAS} and never under {@code new Text("")}.
 */
public final class HiveMetastoreTokens {

  private static final Logger LOG = LoggerFactory.getLogger(HiveMetastoreTokens.class);

  public static final String TOKEN_KIND = "HIVE_DELEGATION_TOKEN";

  /** Credentials key for the Hive token whose service is the default empty signature. */
  static final String CREDENTIALS_ALIAS = "amoro-hive-metastore";

  static final String METASTORE_TOKEN_SIGNATURE = "metastore.token.signature";
  static final String HIVE_METASTORE_TOKEN_SIGNATURE = "hive.metastore.token.signature";

  private HiveMetastoreTokens() {}

  /**
   * Registers copies of {@code token} under every service name Hive may use to select it.
   *
   * @return the service names that were registered, for logging
   */
  public static List<String> addAliases(Credentials credentials, Token<?> token) {
    return addAliases(credentials, token, null);
  }

  public static List<String> addAliases(
      Credentials credentials, Token<?> token, Configuration conf) {
    List<String> services = new ArrayList<>();
    if (credentials == null || token == null) {
      return services;
    }
    for (Text service : lookupServices(token, conf)) {
      credentials.addToken(service, copy(token, service));
      services.add(describe(service));
    }
    // Hive's default signature matches token.getService(), which stays empty. The credentials
    // key must not be empty: that slot belongs to the YARN AM-RM token.
    Text alias = defaultSignatureAlias(credentials);
    credentials.addToken(alias, copy(token, new Text("")));
    services.add("'' via " + alias);
    return services;
  }

  /**
   * Copies the current user's Hive metastore token onto the service names Hive will search. Spark
   * has already installed the token obtained on the driver; this only adds lookup aliases.
   */
  public static void publishToCurrentUser() {
    try {
      UserGroupInformation current = UserGroupInformation.getCurrentUser();
      Credentials credentials = current.getCredentials();
      Token<?> hiveToken = findHiveToken(credentials);
      if (hiveToken == null) {
        LOG.warn("No Hive metastore delegation token is present; Hive will try Kerberos");
        return;
      }
      Configuration conf = new Configuration(false);
      conf.addResource("hive-site.xml");
      Credentials aliases = new Credentials();
      List<String> services = addAliases(aliases, hiveToken, conf);
      for (Map.Entry<Text, Token<? extends TokenIdentifier>> entry :
          aliases.getTokenMap().entrySet()) {
        current.addToken(entry.getKey(), entry.getValue());
      }
      LOG.info("Hive metastore delegation token registered for lookup services {}", services);
    } catch (IOException e) {
      LOG.warn("Could not register the Hive metastore delegation token for the Hive client", e);
    }
  }

  private static Text defaultSignatureAlias(Credentials credentials) {
    Text alias = new Text(CREDENTIALS_ALIAS);
    if (credentials.getToken(alias) == null) {
      return alias;
    }
    return new Text(CREDENTIALS_ALIAS + "-default");
  }

  private static Set<Text> lookupServices(Token<?> token, Configuration conf) {
    Set<Text> services = new LinkedHashSet<>();
    Text current = token.getService();
    if (current != null && current.getLength() > 0) {
      services.add(new Text(current));
    }
    if (conf != null) {
      addConfiguredService(services, conf.get(METASTORE_TOKEN_SIGNATURE));
      addConfiguredService(services, conf.get(HIVE_METASTORE_TOKEN_SIGNATURE));
    }
    return services;
  }

  private static void addConfiguredService(Set<Text> services, String signature) {
    if (signature != null && !signature.isEmpty()) {
      services.add(new Text(signature));
    }
  }

  private static Token<?> findHiveToken(Credentials credentials) {
    if (credentials == null) {
      return null;
    }
    for (Token<?> token : credentials.getAllTokens()) {
      if (isHiveToken(token)) {
        return token;
      }
    }
    return null;
  }

  private static boolean isHiveToken(Token<?> token) {
    return token != null && TOKEN_KIND.equals(String.valueOf(token.getKind()));
  }

  private static Token<TokenIdentifier> copy(Token<?> token, Text service) {
    return new Token<>(token.getIdentifier(), token.getPassword(), token.getKind(), service);
  }

  private static String describe(Text service) {
    return service.getLength() == 0 ? "''" : service.toString();
  }
}
