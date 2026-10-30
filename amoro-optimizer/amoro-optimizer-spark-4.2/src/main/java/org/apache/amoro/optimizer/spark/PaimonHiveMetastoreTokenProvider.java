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

package org.apache.amoro.optimizer.spark;

import org.apache.amoro.hive.HiveMetastoreTokens;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.ql.metadata.Hive;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.TokenIdentifier;
import org.apache.spark.SparkConf;
import org.apache.spark.security.HadoopDelegationTokenProvider;
import org.apache.spark.sql.hive.client.HiveClientImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Option;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Obtains a Hive Metastore delegation token on the Spark driver and stores it in the credentials
 * Spark ships to executors.
 *
 * <p>Spark's own Hive provider skips this when {@code spark.kerberos.keytab} is set, because it
 * assumes only the driver talks to the metastore. Paimon compaction opens {@code HiveCatalog} on
 * the executor, which has no Kerberos TGT in cluster mode.
 */
public class PaimonHiveMetastoreTokenProvider implements HadoopDelegationTokenProvider {

  private static final Logger LOG = LoggerFactory.getLogger(PaimonHiveMetastoreTokenProvider.class);

  static final String SERVICE_NAME = "amoro-hive-metastore";

  private static final String METASTORE_URIS = "hive.metastore.uris";
  private static final String METASTORE_PRINCIPAL = "hive.metastore.kerberos.principal";
  private static final String HIVE_TOKEN_KIND = "HIVE_DELEGATION_TOKEN";

  @Override
  public String serviceName() {
    return SERVICE_NAME;
  }

  @Override
  public boolean delegationTokensRequired(SparkConf sparkConf, Configuration hadoopConf) {
    if (!UserGroupInformation.isSecurityEnabled()) {
      return false;
    }
    if (metastoreUris(hadoopConf).isEmpty()) {
      return false;
    }
    return !hasHiveToken();
  }

  @Override
  public Option<Object> obtainDelegationTokens(
      Configuration hadoopConf, SparkConf sparkConf, Credentials creds) {
    HiveConf conf = new HiveConf(hadoopConf, HiveConf.class);
    String uris = conf.getTrimmed(METASTORE_URIS, "");
    String principal = conf.getTrimmed(METASTORE_PRINCIPAL, "");
    if (uris.isEmpty() || principal.isEmpty()) {
      LOG.info(
          "Skip Hive metastore delegation token; {} or {} is not set",
          METASTORE_URIS,
          METASTORE_PRINCIPAL);
      return empty();
    }
    try {
      String owner = UserGroupInformation.getCurrentUser().getUserName();
      String tokenUrl = delegationToken(conf, owner, principal);
      Token<TokenIdentifier> token = decode(tokenUrl, principal);
      List<String> services = HiveMetastoreTokens.addAliases(creds, token, conf);
      LOG.info(
          "Obtained Hive metastore delegation token for {} (lookup services: {})", owner, services);
      return renewal(token, conf);
    } catch (Exception e) {
      LOG.warn("Failed to obtain Hive metastore delegation token for Paimon compact", e);
      return empty();
    } finally {
      closeHive();
    }
  }

  private static String delegationToken(HiveConf conf, String owner, String renewer)
      throws Exception {
    Hive hive = HiveClientImpl.getHive(conf);
    try {
      return (String)
          hive.getClass()
              .getMethod("getDelegationToken", String.class, String.class)
              .invoke(hive, owner, renewer);
    } catch (java.lang.reflect.InvocationTargetException e) {
      Throwable cause = e.getCause() == null ? e : e.getCause();
      if (cause instanceof Exception) {
        throw (Exception) cause;
      }
      throw e;
    }
  }

  private static Token<TokenIdentifier> decode(String tokenUrl, String principal)
      throws IOException {
    Token<TokenIdentifier> token = new Token<>();
    token.decodeFromUrlString(tokenUrl);
    Text service = token.getService();
    if (service == null || service.getLength() == 0) {
      service = new Text(principal);
      token.setService(service);
    }
    return token;
  }

  private static Option<Object> renewal(Token<TokenIdentifier> token, HiveConf conf) {
    try {
      return some(token.renew(conf));
    } catch (UnsupportedOperationException e) {
      LOG.info(
          "No TokenRenewer for Hive metastore delegation tokens on this classpath; "
              + "the token stays valid until it expires");
      return some(System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1));
    } catch (Exception e) {
      LOG.warn("Could not renew Hive metastore delegation token; will retry later", e);
      return some(System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1));
    }
  }

  private static String metastoreUris(Configuration hadoopConf) {
    try {
      HiveConf conf = new HiveConf(hadoopConf, HiveConf.class);
      String uris = conf.getTrimmed(METASTORE_URIS, "");
      return uris == null ? "" : uris.trim();
    } catch (Exception e) {
      LOG.warn("Could not read Hive metastore configuration", e);
      return "";
    }
  }

  private static boolean hasHiveToken() {
    try {
      Credentials credentials = UserGroupInformation.getCurrentUser().getCredentials();
      if (credentials == null) {
        return false;
      }
      for (Token<?> token : credentials.getAllTokens()) {
        if (token != null && HIVE_TOKEN_KIND.equals(String.valueOf(token.getKind()))) {
          return true;
        }
      }
      return false;
    } catch (IOException e) {
      LOG.warn("Could not inspect Kerberos credentials for a Hive metastore token", e);
      return false;
    }
  }

  private static void closeHive() {
    try {
      Hive.closeCurrent();
    } catch (Exception e) {
      LOG.debug("Failed to close the current Hive client", e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Option<Object> some(long value) {
    return (Option<Object>) (Option<?>) scala.Option.apply(Long.valueOf(value));
  }

  @SuppressWarnings("unchecked")
  private static Option<Object> empty() {
    return (Option<Object>) (Option<?>) scala.Option.empty();
  }
}
