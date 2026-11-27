// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.asyncapi

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.jsonSchema.impl.inspections.JsonSchemaComplianceInspection
import org.jetbrains.yaml.schema.YamlJsonSchemaHighlightingInspection

class AsyncApiSchemaTest : BasePlatformTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.asyncapi,org.jetbrains.plugins.yaml,com.intellij.modules.json")
    }
  }

  override fun setUp() {
    super.setUp()
    myFixture.enableInspections(YamlJsonSchemaHighlightingInspection::class.java, JsonSchemaComplianceInspection::class.java)
  }

  private fun warnings(): List<String> = myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).mapNotNull { it.description }

  fun testVersionComesFromTheTopOfTheFile() {
    val v3 = myFixture.configureByText("a.yaml", "asyncapi: 3.0.0\ninfo: {}\n").virtualFile
    val v2 = myFixture.configureByText("b.json", """{"asyncapi": "2.6.0"}""").virtualFile
    val other = myFixture.configureByText("c.yaml", "openapi: 3.0.0\n").virtualFile
    assertEquals(3, AsyncApiVersion.of(v3))
    assertEquals(2, AsyncApiVersion.of(v2))
    assertNull(AsyncApiVersion.of(other))
  }

  fun testV3OperationKeysAreCompleted() {
    myFixture.configureByText(
      "api.yaml",
      """
      asyncapi: 3.0.0
      info:
        title: Orders
        version: 1.0.0
      operations:
        placeOrder:
          act<caret>
      """.trimIndent(),
    )
    myFixture.completeBasic()
    // the only variant is inserted at once, so there is no lookup list then
    val variants = myFixture.lookupElementStrings ?: listOf(myFixture.editor.document.text.lines().last().trim())
    assertTrue(variants.toString(), variants.any { it.startsWith("action") })
  }

  fun testV3RejectsUnknownActionAndRootKey() {
    myFixture.configureByText(
      "api.yaml",
      """
      asyncapi: 3.0.0
      info:
        title: Orders
        version: 1.0.0
      chanels: {}
      operations:
        placeOrder:
          action: publish
          channel:
            ${'$'}ref: '#/channels/orders'
      """.trimIndent(),
    )
    val found = warnings()
    assertTrue(found.toString(), found.any { "chanels" in it })
    assertTrue(found.toString(), found.any { "publish" in it || "send" in it })
  }

  fun testV2NeedsChannelsAndValidatesJson() {
    myFixture.configureByText("api.json", """{"asyncapi": "2.6.0", "info": {"title": "Orders", "version": "1"}}""")
    assertTrue(warnings().toString(), warnings().any { "channels" in it })
  }

  fun testValidV2DocumentHasNoWarnings() {
    myFixture.configureByText(
      "api.yaml",
      """
      asyncapi: 2.6.0
      info:
        title: Orders
        version: 1.0.0
      channels:
        orders:
          subscribe:
            message:
              ${'$'}ref: '#/components/messages/Order'
      components:
        messages:
          Order:
            payload:
              type: object
      """.trimIndent(),
    )
    assertEmpty(warnings())
  }

  fun testOtherYamlIsNotChecked() {
    myFixture.configureByText("app.yaml", "server:\n  port: 8080\n")
    assertEmpty(warnings())
  }
}
