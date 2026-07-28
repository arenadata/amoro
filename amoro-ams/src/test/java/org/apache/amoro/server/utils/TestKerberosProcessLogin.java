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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.amoro.config.Configurations;
import org.apache.amoro.server.AmoroManagementConf;
import org.apache.amoro.server.util.KerberizedTestHelper;
import org.apache.hadoop.security.UserGroupInformation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

public class TestKerberosProcessLogin extends KerberizedTestHelper {

  @AfterEach
  public void cleanup() {
    KerberosProcessLogin.stop();
    UserGroupInformation.reset();
    System.clearProperty("java.security.krb5.conf");
  }

  @Test
  public void disabledIsNoOp() throws Exception {
    KerberosProcessLogin.start(new Configurations());
    assertFalse(UserGroupInformation.isSecurityEnabled());
  }

  @Test
  public void missingPrincipalFails() {
    Configurations conf = new Configurations();
    conf.set(AmoroManagementConf.KERBEROS_ENABLED, true);
    conf.set(AmoroManagementConf.KERBEROS_KEYTAB, testKeytab);
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> KerberosProcessLogin.start(conf));
    assertTrue(e.getMessage().contains(AmoroManagementConf.KERBEROS_PRINCIPAL.key()));
  }

  @Test
  public void missingKeytabFileFails() {
    Configurations conf = new Configurations();
    conf.set(AmoroManagementConf.KERBEROS_ENABLED, true);
    conf.set(AmoroManagementConf.KERBEROS_PRINCIPAL, testPrincipal);
    conf.set(AmoroManagementConf.KERBEROS_KEYTAB, "/no/such/file.keytab");
    IOException e = assertThrows(IOException.class, () -> KerberosProcessLogin.start(conf));
    assertTrue(e.getMessage().contains("does not exist"));
  }

  @Test
  public void loginFromKeytab() throws Exception {
    Configurations conf = new Configurations();
    conf.set(AmoroManagementConf.KERBEROS_ENABLED, true);
    conf.set(AmoroManagementConf.KERBEROS_PRINCIPAL, testPrincipal);
    conf.set(AmoroManagementConf.KERBEROS_KEYTAB, testKeytab);
    conf.set(AmoroManagementConf.KERBEROS_KRB5_CONF_PATH, krb5ConfPath);

    KerberosProcessLogin.start(conf);

    assertTrue(UserGroupInformation.isSecurityEnabled());
    UserGroupInformation loginUser = UserGroupInformation.getLoginUser();
    assertEquals(testPrincipal, loginUser.getUserName());
    assertEquals(
        UserGroupInformation.AuthenticationMethod.KERBEROS, loginUser.getAuthenticationMethod());
    assertTrue(loginUser.isFromKeytab());
    assertEquals(loginUser, UserGroupInformation.getCurrentUser());
  }
}
