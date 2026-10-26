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

package org.apache.amoro.serialization;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.apache.amoro.paimon.PaimonCompactInput;
import org.apache.amoro.utils.SerializationUtil;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

/**
 * Client-mode Spark installs a context classloader that cannot see optimizer-jar classes.
 * Deserialization still has to find them from the loader that called it.
 */
public class TestSimpleSerializer {

  @Test
  public void deserializesWhenContextClassLoaderHidesAmoroClasses() {
    PaimonCompactInput input =
        new PaimonCompactInput().setDatabase("paimon_db").setTableName("paimon_user");
    ByteBuffer bytes = SerializationUtil.simpleSerialize(input);
    ClassLoader previous = Thread.currentThread().getContextClassLoader();
    Thread.currentThread().setContextClassLoader(hidingAmoroClasses());
    try {
      PaimonCompactInput restored = SerializationUtil.simpleDeserialize(bytes.array());
      assertEquals("paimon_db", restored.getDatabase());
      assertEquals("paimon_user", restored.getTableName());
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
    }
  }

  private static ClassLoader hidingAmoroClasses() {
    return new ClassLoader(null) {
      @Override
      public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.startsWith("org.apache.amoro.")) {
          throw new ClassNotFoundException(name);
        }
        return super.loadClass(name, resolve);
      }
    };
  }
}
