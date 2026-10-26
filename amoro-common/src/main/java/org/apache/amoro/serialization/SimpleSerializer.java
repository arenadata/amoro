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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class SimpleSerializer<R> implements ResourceSerde<R> {

  @Override
  public byte[] serializeResource(R resource) {
    try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
      try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
        oos.writeObject(resource);
        oos.flush();
        return bos.toByteArray();
      }
    } catch (IOException e) {
      throw new IllegalArgumentException("serialization error of " + resource, e);
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public R deserializeResource(byte[] input) {
    if (input == null) {
      return null;
    }
    try (ByteArrayInputStream bis = new ByteArrayInputStream(input)) {
      try (ObjectInputStream ois = new ResolvingObjectInputStream(bis)) {
        return (R) ois.readObject();
      }
    } catch (IOException | ClassNotFoundException e) {
      throw new IllegalArgumentException("deserialization error ", e);
    }
  }

  /**
   * Resolves classes from the calling optimizer code, not only from Spark's thread context loader.
   * In YARN client mode {@code spark.driver.userClassPathFirst} installs a child-first loader that
   * does not see classes inside the optimizer jar, so the default {@link ObjectInputStream} fails
   * on {@code PaimonCompactInput}.
   */
  private static final class ResolvingObjectInputStream extends ObjectInputStream {

    private ResolvingObjectInputStream(InputStream in) throws IOException {
      super(in);
    }

    @Override
    protected Class<?> resolveClass(ObjectStreamClass desc)
        throws IOException, ClassNotFoundException {
      String name = desc.getName();
      ClassNotFoundException last = null;
      for (ClassLoader loader : loaders()) {
        try {
          return Class.forName(name, false, loader);
        } catch (ClassNotFoundException e) {
          last = e;
        }
      }
      try {
        return super.resolveClass(desc);
      } catch (ClassNotFoundException e) {
        if (last != null) {
          e.addSuppressed(last);
        }
        throw e;
      }
    }

    private static List<ClassLoader> loaders() {
      Set<ClassLoader> loaders = new LinkedHashSet<>();
      ClassLoader context = Thread.currentThread().getContextClassLoader();
      if (context != null) {
        loaders.add(context);
      }
      addStackLoaders(loaders);
      ClassLoader self = SimpleSerializer.class.getClassLoader();
      if (self != null) {
        loaders.add(self);
      }
      ClassLoader system = ClassLoader.getSystemClassLoader();
      if (system != null) {
        loaders.add(system);
      }
      return new ArrayList<>(loaders);
    }

    private static void addStackLoaders(Set<ClassLoader> loaders) {
      try {
        StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
            .forEach(
                frame -> {
                  ClassLoader loader = frame.getDeclaringClass().getClassLoader();
                  if (loader != null) {
                    loaders.add(loader);
                  }
                });
      } catch (SecurityException ignored) {
        // A security manager that refuses stack inspection still leaves the other loaders.
      }
    }
  }
}
