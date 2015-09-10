/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.util.lang;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.io.FileUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.SoftReference;
import java.net.URL;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static com.intellij.openapi.util.Pair.pair;

class JarLoader extends Loader {
  private static final List<Pair<Resource.Attribute, Attributes.Name>> PACKAGE_FIELDS = Arrays.asList(
    pair(Resource.Attribute.SPEC_TITLE, Attributes.Name.SPECIFICATION_TITLE),
    pair(Resource.Attribute.SPEC_VERSION, Attributes.Name.SPECIFICATION_VERSION),
    pair(Resource.Attribute.SPEC_VENDOR, Attributes.Name.SPECIFICATION_VENDOR),
    pair(Resource.Attribute.IMPL_TITLE, Attributes.Name.IMPLEMENTATION_TITLE),
    pair(Resource.Attribute.IMPL_VERSION, Attributes.Name.IMPLEMENTATION_VERSION),
    pair(Resource.Attribute.IMPL_VENDOR, Attributes.Name.IMPLEMENTATION_VENDOR));

  private final File myCanonicalFile;
  private final boolean myCanLockJar; // true implies that the zipfile will not be modified in the lifetime of the JarLoader
  private SoftReference<JarMemoryLoader> myMemoryLoader;
  private volatile SoftReference<ZipFile> myZipFileSoftReference; // Used only when myCanLockJar==true
  private final Map<Resource.Attribute, String> myAttributes;

  JarLoader(URL url, @SuppressWarnings("unused") boolean canLockJar, int index, boolean preloadJarContents) throws IOException {
    super(new URL("jar", "", -1, url + "!/"), index);

    myCanonicalFile = new File(FileUtil.unquote(url.getFile())).getCanonicalFile();
    myCanLockJar = canLockJar;

    ZipFile zipFile = getZipFile(); // IOException from opening is propagated to caller if zip file isn't valid,
    try {
      myAttributes = getAttributes(zipFile);
      if (preloadJarContents) {
        JarMemoryLoader loader = JarMemoryLoader.load(zipFile, getBaseURL(), myAttributes);
        if (loader != null) {
          myMemoryLoader = new SoftReference<JarMemoryLoader>(loader);
        }
      }
    }
    finally {
      releaseZipFile(zipFile);
    }
  }

  @Nullable
  private static Map<Resource.Attribute, String> getAttributes(ZipFile zipFile) throws IOException {
    Map<Resource.Attribute, String> map = null;

    ZipEntry entry = zipFile.getEntry(JarFile.MANIFEST_NAME);
    if (entry != null) {
      InputStream stream = zipFile.getInputStream(entry);
      try {
        Attributes attributes = new Manifest(stream).getMainAttributes();
        for (Pair<Resource.Attribute, Attributes.Name> p : PACKAGE_FIELDS) {
          String value = attributes.getValue(p.second);
          if (value != null) {
            if (map == null) map = new EnumMap<Resource.Attribute, String>(Resource.Attribute.class);
            map.put(p.first, value);
          }
        }
      }
      finally {
        stream.close();
      }
    }

    return map;
  }

  @NotNull
  @Override
  public ClasspathCache.LoaderData buildData() throws IOException {
    ZipFile zipFile = getZipFile();
    try {
      ClasspathCache.LoaderData loaderData = new ClasspathCache.LoaderData();
      Enumeration<? extends ZipEntry> entries = zipFile.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        String name = entry.getName();
        loaderData.addResourceEntry(name);
        loaderData.addNameEntry(name);
      }
      return loaderData;
    }
    finally {
      releaseZipFile(zipFile);
    }
  }

  private static final AtomicInteger myGetResourceRequests = new AtomicInteger();
  private static final AtomicLong myOpenTime = new AtomicLong();
  private static final AtomicLong myCloseTime = new AtomicLong();

  @Override
  @Nullable
  Resource getResource(String name, boolean flag) {
    JarMemoryLoader loader = myMemoryLoader != null? myMemoryLoader.get() : null;
    if (loader != null) {
      Resource resource = loader.getResource(name);
      if (resource != null) return resource;
    }

    try {
      ZipFile zipFile = getZipFile();
      try {
        ZipEntry entry = zipFile.getEntry(name);
        if (entry != null) {
          return MemoryResource.load(getBaseURL(), zipFile, entry, myAttributes);
        }
      }
      finally {
        releaseZipFile(zipFile);
      }
    }
    catch (Exception e) {
      error("file: " + myCanonicalFile, e);
    }

    return null;
  }

  protected void error(String message, Throwable t) {
    //Logger.getLogger(JarLoader.class.getName()).log(Level.SEVERE, message, t);
    Logger.getInstance(JarLoader.class).error(message, t);
  }

  private void releaseZipFile(ZipFile zipFile) throws IOException {
    long started = System.nanoTime();
    try {
      // Closing of zip file when myCanLockJar=true happens in ZipFile.finalize
      if (!myCanLockJar) {
        zipFile.close();
      }
    } finally {
      myCloseTime.addAndGet(System.nanoTime() - started);
    }
  }

  @NotNull
  private ZipFile getZipFile() throws IOException {
    @SuppressWarnings("unused") int requests = myGetResourceRequests.incrementAndGet();

    long started = System.nanoTime();
    try {

      // This code is executed at least 100K times (O(number of classes needed to load)) and it takes considerable time to open ZipFile's
      // such number of times so we store reference to ZipFile if we allowed to lock the file (assume it isn't changed)
      if (myCanLockJar) {
        SoftReference<ZipFile> zipFileSoftReference = myZipFileSoftReference;
        if (zipFileSoftReference != null) {
          ZipFile existingZipFile = zipFileSoftReference.get();
          if (existingZipFile != null) return existingZipFile;
        }
        synchronized (ourLock) {
          zipFileSoftReference = myZipFileSoftReference;
          if (zipFileSoftReference != null) {
            ZipFile existingZipFile = zipFileSoftReference.get();
            if (existingZipFile != null) return existingZipFile;
          }
          // ZipFile's native implementation (ZipFile.c, zip_util.c) has path -> file descriptor cache
          ZipFile zipFile = new ZipFile(myCanonicalFile);
          myZipFileSoftReference = new SoftReference<ZipFile>(zipFile);
          return zipFile;
        }
      }
      else {
        return new ZipFile(myCanonicalFile);
      }
    } finally {
      myOpenTime.addAndGet(System.nanoTime() - started);
      //if (requests % 1000 == 0) {
      //  int factor = 1000000;
      //  System.out.println(
      //    "Jar loading :" + getClass().getClassLoader() + "," + requests + ", ot:" + (myOpenTime.get() / factor) + ", ct:" +
      //    (myCloseTime.get() / factor));
      //}
    }
  }

  @Override
  public String toString() {
    return "JarLoader [" + myCanonicalFile + "]";
  }

  private static final Object ourLock = new Object();
}
