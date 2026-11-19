package com.phantom.mermaid.lang.parser

class XYChartTest : MermaidParserTestCase("xychart") {
  fun `test simple xychart`() = doTest(true)
}
