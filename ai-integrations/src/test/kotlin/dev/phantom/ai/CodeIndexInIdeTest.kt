// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.ai.mcp.CodeQueries
import dev.phantom.ai.mcp.McpToolError

class CodeIndexInIdeTest : BasePlatformTestCase() {
  private lateinit var order: VirtualFile
  private lateinit var queries: CodeQueries

  override fun setUp() {
    super.setUp()
    order = myFixture.addFileToProject("shop/OrderService.java", """
      package shop;
      public class OrderService {
        public int total(int price) { return price * 2; }
      }
    """.trimIndent()).virtualFile
    myFixture.addFileToProject("shop/Checkout.java", """
      package shop;
      public class Checkout {
        int pay(OrderService orders) { return orders.total(3); }
      }
    """.trimIndent())
    queries = CodeQueries(project, GlobalSearchScope.projectScope(project)) { it.name }
  }

  fun testFindsAClassAndAMethodWithTheirLine() {
    assertEquals("OrderService.java:2  public class OrderService {", ReadAction.compute<String, Exception> { queries.symbols("OrderService", 10) })
    assertEquals("OrderService.java:3  public int total(int price) { return price * 2; }", ReadAction.compute<String, Exception> { queries.symbols("total", 10) })
  }

  fun testCamelHumpsMatchAndLimitSaysThereIsMore() {
    assertTrue(ReadAction.compute<String, Exception> { queries.symbols("OS", 10) }.startsWith("OrderService.java:2"))
    val two = ReadAction.compute<String, Exception> { queries.symbols("t", 1) }
    assertEquals(2, two.lines().size)
    assertTrue(two.lines().last().startsWith("... more"))
  }

  fun testFindsFilesByName() {
    assertEquals("Checkout.java", ReadAction.compute<String, Exception> { queries.files("Checkout", 10) })
    assertEquals("No file name matches \"Nope\" in this workspace.", ReadAction.compute<String, Exception> { queries.files("Nope", 10) })
  }

  fun testUsagesFromTheDeclaration() {
    assertEquals("Checkout.java:3  int pay(OrderService orders) { return orders.total(3); }",
                 ReadAction.compute<String, Exception> { queries.usages(order, 3, "total", 10) })
  }

  fun testAWrongLineIsAnErrorNotAGuess() {
    assertThrows(McpToolError::class.java) { ReadAction.run<Exception> { queries.usages(order, 1, "total", 10) } }
    assertThrows(McpToolError::class.java) { ReadAction.run<Exception> { queries.usages(order, 99, "total", 10) } }
  }

  fun testOutlineGivesDeclarationsWithLines() {
    val outline = ReadAction.compute<String, Exception> { queries.outline(order) }.lines()
    assertEquals("2  OrderService", outline[0])
    assertTrue(outline[1], outline[1].startsWith("  3  total"))
  }
}
