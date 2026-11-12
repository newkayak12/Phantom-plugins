package com.phantom.mermaid.lang.intention.gitgraph

import com.phantom.mermaid.MermaidBundle
import com.phantom.mermaid.lang.MermaidBaseTestCase
import com.phantom.mermaid.lang.intention.GitGraphInspection

class CreateCommitDeclarationIntentionTest : MermaidBaseTestCase("intention/gitgraph/create_commit") {
  fun `test cherry-pick`() = doTest()

  private fun doTest() {
    val testName = getTestName(true)
    myFixture.configureByFile("${testName}_before.mermaid")
    myFixture.enableInspections(GitGraphInspection())

    val targetText = MermaidBundle.message("fix.create.commit.declaration", "123")
    val fix = myFixture.getAllQuickFixes().find { it.text == targetText }
    assertNotNull(fix)
    myFixture.checkPreviewAndLaunchAction(fix!!)
    myFixture.checkResultByFile("${testName}_after.mermaid")
  }
}
