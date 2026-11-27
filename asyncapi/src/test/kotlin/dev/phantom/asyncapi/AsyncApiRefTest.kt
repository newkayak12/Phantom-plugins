// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.asyncapi

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.yaml.psi.YAMLKeyValue

class AsyncApiRefTest : BasePlatformTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.asyncapi,org.jetbrains.plugins.yaml,com.intellij.modules.json")
    }
  }

  override fun setUp() {
    super.setUp()
    myFixture.enableInspections(AsyncApiRefInspection::class.java)
    myFixture.addFileToProject("messages/order.yaml", "Order:\n  payload:\n    type: object\n")
  }

  private fun errors(): List<String> = myFixture.doHighlighting(HighlightSeverity.ERROR).filter { it.inspectionToolId == "AsyncApiRef" }
    .mapNotNull { it.description }.sorted()

  private fun spec(ref: String) = """
    asyncapi: 3.0.0
    info:
      title: Shop
      version: 1.0.0
    channels:
      orders:
        address: orders
        messages:
          placed:
            ${'$'}ref: '$ref'
    components:
      messages:
        OrderPlaced:
          payload:
            type: object
  """.trimIndent()

  fun testLocalMessageRefResolves() {
    myFixture.configureByText("api.yaml", spec("#/components/messages/Order<caret>Placed"))
    val target = myFixture.file.findReferenceAt(myFixture.caretOffset)?.resolve()
    assertEquals("OrderPlaced", (target as YAMLKeyValue).keyText)
    assertEmpty(errors())
  }

  fun testRefToAnotherFileResolves() {
    myFixture.configureByText("api.yaml", spec("messages/order.yaml#/Or<caret>der"))
    val target = myFixture.file.findReferenceAt(myFixture.caretOffset)?.resolve()
    assertEquals("order.yaml", target?.containingFile?.name)
    assertEmpty(errors())
  }

  fun testUnresolvedRefsAreErrorsAndRemoteOnesAreNot() {
    myFixture.configureByText("api.yaml", spec("#/components/messages/Missing"))
    assertEquals(listOf("Cannot resolve #/components/messages/Missing"), errors())
    myFixture.configureByText("b.yaml", spec("missing.yaml#/Order"))
    assertEquals(listOf("Cannot resolve missing.yaml#/Order"), errors())
    myFixture.configureByText("c.yaml", spec("https://example.com/order.yaml"))
    assertEmpty(errors())
  }

  fun testJsonRefResolves() {
    myFixture.configureByText(
      "api.json",
      """{"asyncapi": "2.6.0", "channels": {"orders": {"subscribe": {"message": {"${'$'}ref": "#/components/messages/O<caret>rder"}}}},
        |"components": {"messages": {"Order": {}}}}""".trimMargin(),
    )
    assertNotNull(myFixture.file.findReferenceAt(myFixture.caretOffset)?.resolve())
    assertEmpty(errors())
  }

  fun testCompletionOffersComponentsAndChannels() {
    myFixture.configureByText("api.yaml", spec("#/<caret>"))
    myFixture.completeBasic()
    assertContainsElements(myFixture.lookupElementStrings.orEmpty(), "#/components/messages/OrderPlaced", "#/channels/orders")
  }

  fun testRenameKeepsThePointer() {
    myFixture.configureByText("api.yaml", spec("#/components/messages/Order<caret>Placed"))
    myFixture.renameElementAtCaret("OrderCreated")
    assertTrue(myFixture.editor.document.text, "'#/components/messages/OrderCreated'" in myFixture.editor.document.text)
  }

  fun testRefsOutsideAsyncApiAreIgnored() {
    myFixture.configureByText("other.yaml", "a:\n  ${'$'}ref: '#/missing'\n")
    assertNull(myFixture.file.findReferenceAt(myFixture.file.text.indexOf("#/missing") + 1))
    assertEmpty(errors())
  }
}
