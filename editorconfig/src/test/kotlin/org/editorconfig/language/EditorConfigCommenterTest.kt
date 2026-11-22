// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package org.editorconfig.language

import com.intellij.codeInsight.generation.actions.CommentByLineCommentAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class EditorConfigCommenterTest : BasePlatformTestCase() {
  override fun getTestDataPath() =
    "${org.editorconfig.EditorConfigTestRoot.PATH}/testData/org/editorconfig/language/commenter/"

  fun testComment() = doTest()
  fun testUncomment() = doTest()

  private fun doTest() {
    val name = getTestName(true)
    val source = "$name/.editorconfig"
    myFixture.configureByFile(source)
    CommentByLineCommentAction().actionPerformedImpl(project, myFixture.editor)
    val result = "$name/result.txt"
    myFixture.checkResultByFile(result)
  }
}
