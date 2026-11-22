// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package org.intellij.plugins.markdown

import com.intellij.openapi.util.io.FileUtil
import java.io.File

internal object MarkdownTestingUtil {
  // Phantom-plugins: the test data is under the plugin's folder, not the IDE home
  @JvmField
  val TEST_DATA_PATH: String = FileUtil.toSystemIndependentName(File("testData").absolutePath)
}
