// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localsharedindex.agent

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.FileDocumentManagerImpl
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CodeQueriesTest : BasePlatformTestCase() {
  private lateinit var order: VirtualFile
  private lateinit var queries: CodeQueries

  override fun setUp() {
    // A heavy test of this module that ran before in the same JVM (LocalSharedIndexAliasRestartTest) can leave an
    // unsaved document of a checkout it deleted; LightPlatformTestCase refuses to start while there is one.
    ApplicationManager.getApplication()?.invokeAndWait {
      WriteAction.run<Exception> { (FileDocumentManager.getInstance() as FileDocumentManagerImpl).dropAllUnsavedDocuments() }
    }
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
    assertThrows(IndexToolError::class.java) { ReadAction.run<Exception> { queries.usages(order, 1, "total", 10) } }
    assertThrows(IndexToolError::class.java) { ReadAction.run<Exception> { queries.usages(order, 99, "total", 10) } }
  }

  fun testSymbolInfoFromAUseGivesTheDeclarationSignatureAndDoc() {
    val doc = myFixture.addFileToProject("shop/Prices.java", """
      package shop;
      public class Prices {
        /**
         * Price after tax.
         * @param net before tax
         */
        public static int gross(int net) { return net + 1; }
        int use() { return gross(2); }
      }
    """.trimIndent()).virtualFile
    assertEquals(
      "Prices.java:7  public static int gross(int net) { return net + 1; }\npublic static int gross(int net)\nPrice after tax. @param net before tax",
      ReadAction.compute<String, Exception> { queries.symbolInfo(doc, 8, "gross") },
    )
  }

  fun testCallsListCallersWithTheirMemberAndWhatItCalls() {
    myFixture.addFileToProject("shop/Cart.java", """
      package shop;
      public class Cart {
        int sum(OrderService orders) { return orders.total(1) + tax(); }
        int tax() { return 0; }
      }
    """.trimIndent())
    val callers = ReadAction.compute<String, Exception> { queries.calls(order, 3, "total", 10) }.lines()
    assertEquals("Called from:", callers[0])
    assertTrue(callers.toString(), callers.contains("Checkout.java:3  [pay(OrderService): int]  int pay(OrderService orders) { return orders.total(3); }"))
    assertTrue(callers.toString(), callers.any { it.startsWith("Cart.java:3  [sum(OrderService): int]") })
    assertEquals("nothing in this workspace", callers.last())

    val cart = ReadAction.compute<String, Exception> { queries.calls(myFixture.findFileInTempDir("shop/Cart.java"), 3, "sum", 10) }.lines()
    assertEquals(listOf("Called from:", "nowhere in this workspace", "Calls:"), cart.take(3))
    assertEquals(setOf("OrderService.java:3  public int total(int price) { return price * 2; }", "Cart.java:4  int tax() { return 0; }"), cart.drop(3).toSet())
  }

  fun testOutlineGivesDeclarationsWithLines() {
    val outline = ReadAction.compute<String, Exception> { queries.outline(order) }.lines()
    assertEquals("2  OrderService", outline[0])
    assertTrue(outline[1], outline[1].startsWith("  3  total"))
  }
}
