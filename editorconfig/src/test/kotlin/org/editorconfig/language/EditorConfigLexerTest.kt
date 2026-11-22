// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package org.editorconfig.language

import com.intellij.editorconfig.common.syntax.lexer.EditorConfigLexerAdapter
import com.intellij.lexer.Lexer
import com.intellij.testFramework.LexerTestCase

// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

class EditorConfigLexerTest : LexerTestCase() {
  override fun createLexer(): Lexer = EditorConfigLexerAdapter()

  override fun getDirPath() = "testData/org/editorconfig/language/lexer"

  // Phantom-plugins: testData/ is in the plugin's folder, not under the IDE home
  override fun getPathToTestDataFile(extension: String) =
    "${org.editorconfig.EditorConfigTestRoot.PATH}/${dirPath}/${getTestName(true)}$extension"

  fun testEmpty() = doTest()
  fun testComment() = doTest()
  fun testKeyValuePair() = doTest()

  fun testSimpleSection() = doTest()
  fun testCharClassSection() = doTest()
  fun testVariantSection() = doTest()
  fun testComplexSection() = doTest()

  fun testWhitespacedComplexSection() = doTest()
  fun testWhitespacedKeyValuePair() = doTest()

  fun testQualifiedName() = doTest()

  private fun doTest() =
    doFileTest("editorconfig")
}
