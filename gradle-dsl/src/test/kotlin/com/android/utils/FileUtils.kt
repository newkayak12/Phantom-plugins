// Phantom: a stand-in for the Android Studio class of the same name, which the upstream tests use but which is not part of
// IntelliJ Community or any published artifact Phantom builds against. Only what these tests call, behaving the same.
package com.android.utils

import java.io.File

object FileUtils {
  @JvmStatic
  fun toSystemDependentPath(path: String): String = if (File.separatorChar != '/') path.replace('/', File.separatorChar) else path

  @JvmStatic
  fun toSystemIndependentPath(path: String): String = if (File.separatorChar != '/') path.replace(File.separatorChar, '/') else path
}
