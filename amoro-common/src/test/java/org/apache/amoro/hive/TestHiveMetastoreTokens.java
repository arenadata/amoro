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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.TokenIdentifier;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Hive selects metastore tokens by service name, which defaults to empty. */
public class TestHiveMetastoreTokens {

  @Test
  public void aliasesTokenForDefaultAndConfiguredSignatures() {
    Token<TokenIdentifier> token = hiveToken();
    Configuration conf = new Configuration(false);
    conf.set(HiveMetastoreTokens.METASTORE_TOKEN_SIGNATURE, "custom-signature");

    Credentials credentials = new Credentials();
    HiveMetastoreTokens.addAliases(credentials, token, conf);

    assertNull(credentials.getToken(new Text("")));
    assertSameSecret(token, select(credentials, ""));
    assertSameSecret(token, select(credentials, "hive/metastore.example@REALM"));
    assertSameSecret(token, select(credentials, "custom-signature"));
    Token<?> stored = credentials.getToken(new Text(HiveMetastoreTokens.CREDENTIALS_ALIAS));
    assertNotNull(stored);
    assertEquals(0, stored.getService().getLength());
  }

  @Test
  public void keepsYarnAmRmTokenWhenSparkMergesDelegationTokens() throws IOException {
    Token<TokenIdentifier> yarn =
        new Token<>(new byte[] {9}, new byte[] {8}, new Text("YARN_AM_RM_TOKEN"), new Text(""));
    Credentials applicationMaster = new Credentials();
    applicationMaster.addToken(yarn.getService(), yarn);

    Credentials obtained = new Credentials();
    HiveMetastoreTokens.addAliases(obtained, hiveToken(), null);
    assertNull(obtained.getToken(new Text("")));

    Credentials shipped = new Credentials();
    shipped.readTokenStorageStream(
        new DataInputStream(new ByteArrayInputStream(serialize(obtained))));
    applicationMaster.addAll(shipped);

    assertArrayEquals(
        yarn.getIdentifier(), applicationMaster.getToken(new Text("")).getIdentifier());
    assertSameSecret(hiveToken(), select(applicationMaster, ""));
  }

  private static Token<TokenIdentifier> hiveToken() {
    return new Token<>(
        new byte[] {1, 2},
        new byte[] {3, 4},
        new Text(HiveMetastoreTokens.TOKEN_KIND),
        new Text("hive/metastore.example@REALM"));
  }

  private static byte[] serialize(Credentials credentials) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    credentials.writeTokenStorageToStream(new DataOutputStream(bytes));
    return bytes.toByteArray();
  }

  private static void assertSameSecret(Token<?> expected, Token<?> actual) {
    assertNotNull(actual);
    assertArrayEquals(expected.getPassword(), actual.getPassword());
    assertArrayEquals(expected.getIdentifier(), actual.getIdentifier());
  }

  /** Same match Hive uses: kind and token service, not only the credentials map key. */
  private static Token<?> select(Credentials credentials, String service) {
    Text expected = new Text(service);
    for (Token<?> token : credentials.getAllTokens()) {
      if (HiveMetastoreTokens.TOKEN_KIND.equals(String.valueOf(token.getKind()))
          && expected.equals(token.getService())) {
        return token;
      }
    }
    return null;
  }
}
