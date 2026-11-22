// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package git4idea.test

import com.intellij.testFramework.TestDataPath
import java.nio.file.Path

@TestDataPath("\$CONTENT_ROOT/../testData")
object TestDataUtil {
  @JvmStatic
  val basePath: Path by lazy {
    // Phantom-plugins: the tests run in the plugin's folder (testData/ there), not in the IntelliJ monorepo
    Path.of("").toAbsolutePath().resolve("testData")
  }
}