package com.phantom.mermaid.lang.intention.nbsp

import com.phantom.mermaid.MermaidBundle
import com.phantom.mermaid.lang.MermaidBaseTestCase

class RemoveSpaceIntentionTest : MermaidBaseTestCase("intention/nbsp/remove_space") {
  fun `test state diagram`() = doTest()

  fun `test flowchart`() = doTest()

  private fun doTest() {
    val testName = getTestName(true)
    myFixture.configureByFile("${testName}_before.mermaid")

    val targetText = MermaidBundle.message("fix.remove.space")
    val fix = myFixture.getAllQuickFixes().find { it.text == targetText }
    assertNotNull(fix)
    myFixture.checkPreviewAndLaunchAction(fix!!)
    myFixture.checkResultByFile("${testName}_after.mermaid")
  }
}
