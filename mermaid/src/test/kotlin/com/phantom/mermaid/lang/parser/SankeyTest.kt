package com.phantom.mermaid.lang.parser

class SankeyTest : MermaidParserTestCase("sankey") {
  fun `test sankey`() = doTest(true)
}
