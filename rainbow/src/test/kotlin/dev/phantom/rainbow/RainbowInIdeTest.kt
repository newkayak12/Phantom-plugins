// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.rainbow

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.codeInsight.daemon.impl.analysis.HighlightInfoHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import dev.phantom.rainbow.core.RainbowColors
import dev.phantom.rainbow.core.RainbowHighlightVisitor

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

  fun `test braces in comments and strings are not brackets`() {
    val result = rainbow("class A {\n  /** See {@link A} and {@code x[0]}. */\n  // f(\n  String s = \"{(\";\n}\n")
      .filter { it.second.contains("BRACKET") }
    assertEquals(listOf("{" to "PHANTOM_RAINBOW_BRACKET_1", "}" to "PHANTOM_RAINBOW_BRACKET_1"), result)
  }

  fun `test indentation of the first line of the file`() {
    val result = rainbow("    class A {}\n").filter { it.second.contains("INDENT") }
    assertEquals(listOf("    " to "PHANTOM_RAINBOW_INDENT_1"), result)
  }

  /** 5k calls in one array initializer: every bracket used to rescan the initializer's 10k children (quadratic). */
  fun `test a wide file is colored`() {
    val n = 5_000
    val text = "class A { int f(int x) { return x; } int[] a = {" + (0 until n).joinToString(",") { "f($it)" } + "}; }"
    val file = myFixture.configureByText("A.java", text)
    val holder = HighlightInfoHolder(file)
    val visitor = RainbowHighlightVisitor()
    val start = System.nanoTime()
    visitor.analyze(file, true, holder) {
      file.accept(object : PsiRecursiveElementWalkingVisitor() {
        override fun visitElement(element: PsiElement) {
          visitor.visit(element)
          super.visitElement(element)
        }
      })
    }
    println("wide file: ${(System.nanoTime() - start) / 1_000_000} ms")
    val parens = (0 until holder.size()).map { holder[it] }.count { text[it.startOffset] == '(' || text[it.startOffset] == ')' }
    assertEquals(2 * n + 2, parens)
  }

  fun `test trailing whitespace at the end of the file is not indentation`() {
    assertTrue(rainbow("class A {}\n    ").none { it.second.contains("INDENT") })
  }
}
