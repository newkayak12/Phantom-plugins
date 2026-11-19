package com.phantom.mermaid.lang.intention.nbsp

import com.phantom.mermaid.lang.MermaidBaseTestCase

class UnrenderableSpacesAnnotatorTest : MermaidBaseTestCase("intention/nbsp") {
  fun `test state diagram`() = doTest()

  fun `test flowchart`() = doTest()

  private fun doTest() {
    val testName = getTestName(true)
    myFixture.configureByFile("${testName}.mermaid")
    myFixture.checkHighlighting()
  }
}
