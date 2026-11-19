// Phantom: a stand-in for the Android Studio class of the same name, which the upstream tests use but which is not part of
// IntelliJ Community or any published artifact Phantom builds against. Only what these tests call, behaving the same.
package com.android.testutils

import java.nio.file.Path
import java.nio.file.Paths

object TestUtils {
  /**
   * Upstream paths are relative to the AOSP workspace, e.g. tools/adt/idea/gradle-dsl/testData/parser; here the test
   * data of each module sits under this plugin's testData/<module>/ (Gradle runs the tests from the plugin folder).
   */
  @JvmStatic
  fun resolveWorkspacePath(path: String): Path {
    val module = path.removePrefix("tools/adt/idea/").replaceFirst("/testData", "")
    return Paths.get("testData", module).toAbsolutePath()
  }
}
