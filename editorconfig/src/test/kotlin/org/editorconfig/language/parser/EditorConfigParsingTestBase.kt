// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.editorconfig.language.parser

import com.intellij.editorconfig.common.plugin.EditorConfigParserDefinition
import com.intellij.testFramework.ParsingTestCase

abstract class EditorConfigParsingTestBase(dataPath: String) : ParsingTestCase(dataPath, "editorconfig", true,
                                                                               EditorConfigParserDefinition()) {
  override fun getTestDataPath() =
    "${org.editorconfig.EditorConfigTestRoot.PATH}/testData/org/editorconfig/language/parser"

  protected fun doTest() = doTest(true)
}
