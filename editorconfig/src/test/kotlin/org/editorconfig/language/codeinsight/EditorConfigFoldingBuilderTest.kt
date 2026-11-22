// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package org.editorconfig.language.codeinsight

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class EditorConfigFoldingBuilderTest : BasePlatformTestCase() {
  override fun getTestDataPath() =
    "${org.editorconfig.EditorConfigTestRoot.PATH}/testData/org/editorconfig/language/codeinsight/folding/"

  fun testSection() = doTest()
  fun testSimpleComment() = doTest()
  fun testBlankLineBetweenComments() = doTest()
  fun testCommentInsideSection() = doTest()
  fun testCommentBetweenHeaderAndOption() = doTest()
  fun testMultipleSections() = doTest()
  fun testTrailingComment() = doTest()
  fun testLongComment() = doTest()

  private fun doTest() =
    myFixture.testFolding("${testDataPath}${getTestName(true)}/.editorconfig")
}
