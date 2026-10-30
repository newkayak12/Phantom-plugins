// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.rainbow

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.rainbow.core.RainbowColors

class RainbowInIdeTest : LightJavaCodeInsightFixtureTestCase() {
  /** Text and key name of every rainbow highlight, in order. */
  private fun rainbow(text: String): List<Pair<String, String>> {
    myFixture.configureByText("A.java", text)
    val keys = (RainbowColors.BRACKETS + RainbowColors.INDENTS).toSet()
    return myFixture.doHighlighting()
      .filter { it.type.attributesKey in keys }
      .sortedBy { it.startOffset }
      .map { text.substring(it.startOffset, it.endOffset) to it.type.attributesKey.externalName }
  }

  fun `test brackets are colored by depth per kind`() {
    val result = rainbow("class A { int f(int[] a) { return g(h(a[0])); } int g(int x) { return x; } int h(int x) { return x; } }")
      .filter { it.second.contains("BRACKET") }
    val parens = result.filter { it.first == "(" }.map { it.second.last() }
    // f( g( h( : f's parameter list, then g's and h's call arguments nested in each other
    assertEquals(listOf('1', '1', '2', '1', '1'), parens)
    val braces = result.filter { it.first == "{" }.map { it.second.last() }
    assertEquals(listOf('1', '2', '2', '2'), braces)
  }

  fun `test comparison operators are not brackets`() {
    assertTrue(rainbow("class A { boolean f(int a) { return a < 1 || a > 2; } }").none { it.first == "<" || it.first == ">" })
  }

  fun `test indentation is colored per level`() {
    val result = rainbow("class A {\n    void f() {\n        int x;\n    }\n}\n").filter { it.second.contains("INDENT") }
    assertEquals(listOf("    " to "PHANTOM_RAINBOW_INDENT_1", "    " to "PHANTOM_RAINBOW_INDENT_1",
                        "    " to "PHANTOM_RAINBOW_INDENT_2", "    " to "PHANTOM_RAINBOW_INDENT_1"), result)
  }
}
