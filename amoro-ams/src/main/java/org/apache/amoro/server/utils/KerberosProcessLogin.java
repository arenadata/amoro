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

import org.apache.amoro.config.Configurations;
import org.apache.amoro.server.AmoroManagementConf;
import org.apache.amoro.shade.guava32.com.google.common.base.Preconditions;
import org.apache.amoro.shade.guava32.com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.amoro.utils.ReflectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.SecurityUtil;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.authentication.util.KerberosName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Logs the AMS process in from a Kerberos keytab and keeps the TGT fresh with a periodic re-login.
 *
 * <p>The login user becomes the process-wide Hadoop identity ({@link
 * UserGroupInformation#getLoginUser()}), used by anything relying on the current UGI: Hadoop
 * credential providers, ZooKeeper HA Kerberos authentication, direct filesystem access. Catalog
 * access is not affected — {@code TableMetaStore} builds isolated UGIs from catalog credentials.
 */
public class KerberosProcessLogin {
  private static final Logger LOG = LoggerFactory.getLogger(KerberosProcessLogin.class);

  private static final String KRB5_CONF_PROPERTY = "java.security.krb5.conf";

  private static ScheduledExecutorService reloginExecutor;

  private KerberosProcessLogin() {}

  /** No-op unless {@code kerberos.enabled} is true. */
  public static synchronized void start(Configurations serviceConfig) throws IOException {
    if (!serviceConfig.getBoolean(AmoroManagementConf.KERBEROS_ENABLED)) {
      return;
    }
    Preconditions.checkState(reloginExecutor == null, "Kerberos process login already started");
    String principal = serviceConfig.get(AmoroManagementConf.KERBEROS_PRINCIPAL);
    String keytab = serviceConfig.get(AmoroManagementConf.KERBEROS_KEYTAB);
    Preconditions.checkArgument(
        StringUtils.isNoneBlank(principal, keytab),
        "%s and %s must be provided when %s is true",
        AmoroManagementConf.KERBEROS_PRINCIPAL.key(),
        AmoroManagementConf.KERBEROS_KEYTAB.key(),
        AmoroManagementConf.KERBEROS_ENABLED.key());
    if (!new File(keytab).exists()) {
      throw new IOException(
          String.format(
              "%s: %s does not exist", AmoroManagementConf.KERBEROS_KEYTAB.key(), keytab));
    }

    String krb5ConfPath = serviceConfig.get(AmoroManagementConf.KERBEROS_KRB5_CONF_PATH);
    if (StringUtils.isNotBlank(krb5ConfPath)) {
      System.setProperty(KRB5_CONF_PROPERTY, krb5ConfPath);
      refreshKrb5Config();
      KerberosName.resetDefaultRealm();
    }

    Configuration hadoopConf = new Configuration();
    hadoopConf.set("hadoop.security.authentication", "kerberos");
    UserGroupInformation.setConfiguration(hadoopConf);
    String resolvedPrincipal = SecurityUtil.getServerPrincipal(principal, "0.0.0.0");
    UserGroupInformation.loginUserFromKeytab(resolvedPrincipal, keytab);
    LOG.info(
        "AMS process logged in as {} from keytab {}",
        UserGroupInformation.getLoginUser().getUserName(),
        keytab);

    Duration reloginInterval = serviceConfig.get(AmoroManagementConf.KERBEROS_RELOGIN_INTERVAL);
    reloginExecutor =
        Executors.newSingleThreadScheduledExecutor(
            new ThreadFactoryBuilder()
                .setDaemon(true)
                .setNameFormat("ams-kerberos-relogin-%d")
                .build());
    reloginExecutor.scheduleWithFixedDelay(
        KerberosProcessLogin::relogin,
        reloginInterval.toMillis(),
        reloginInterval.toMillis(),
        TimeUnit.MILLISECONDS);
  }

  public static synchronized void stop() {
    if (reloginExecutor != null) {
      reloginExecutor.shutdownNow();
      reloginExecutor = null;
    }
  }

  private static void relogin() {
    try {
      // synchronized like TableMetaStore's re-login to avoid racing catalog UGI construction
      synchronized (UserGroupInformation.class) {
        UserGroupInformation.getLoginUser().checkTGTAndReloginFromKeytab();
      }
    } catch (Throwable t) {
      LOG.error("Failed to re-login the AMS process from keytab", t);
    }
  }

  // requires --add-exports=java.security.jgss/sun.security.krb5=ALL-UNNAMED, see ams.sh
  private static void refreshKrb5Config() throws IOException {
    try {
      ReflectionUtils.invoke("sun.security.krb5.Config", "refresh");
    } catch (Exception e) {
      throw new IOException("Failed to refresh krb5 configuration", e);
    }
  }
}
