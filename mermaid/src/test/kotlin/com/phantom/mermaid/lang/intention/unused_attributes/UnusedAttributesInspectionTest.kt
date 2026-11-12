package com.phantom.mermaid.lang.intention.unused_attributes

import com.phantom.mermaid.lang.MermaidBaseTestCase
import com.phantom.mermaid.lang.intention.UnusedAttributesInspection

class UnusedAttributesInspectionTest : MermaidBaseTestCase("intention/unused_attributes") {
  fun `test journey`() = doTest()

  private fun doTest() {
    val testName = getTestName(true)
    myFixture.configureByFile("${testName}.mermaid")
    myFixture.enableInspections(UnusedAttributesInspection())
    myFixture.checkHighlighting()
  }
}
