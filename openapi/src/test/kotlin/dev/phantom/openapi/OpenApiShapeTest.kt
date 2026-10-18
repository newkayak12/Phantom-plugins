// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.openapi

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

/** Files that look like OpenAPI documents but are not read as one, versions, and unknown top-level keys. */
class OpenApiShapeTest : LightJavaCodeInsightFixtureTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.openapi,org.jetbrains.plugins.yaml,com.intellij.modules.json,com.intellij.java")
    }
  }

  override fun getProjectDescriptor() = RealJdk

  override fun setUp() {
    super.setUp()
    myFixture.enableInspections(OpenApiSpecShapeInspection::class.java)
  }

  private fun problems(): List<String> = myFixture.doHighlighting().filter { it.inspectionToolId == "PhantomOpenApiSpecShape" }
    .mapNotNull { it.description }.sorted()

  private fun msg(key: String, vararg params: Any) = PhantomOpenApiBundle.message(key, *params)

  fun testMisspelledOrMissingVersionKey() {
    myFixture.configureByText("api.yaml", "opeanpi: 3.0.3\ninfo:\n  title: x\n  version: '1'\npaths: {}\n")
    assertEquals(listOf(msg("shape.version.key.typo", "opeanpi", "openapi")), problems())
    myFixture.configureByText("api.json", """{ "Swagger": "2.0", "info": {}, "paths": {} }""")
    assertEquals(listOf(msg("shape.version.key.typo", "Swagger", "swagger")), problems())
    myFixture.configureByText("api2.yaml", "info:\n  title: x\npaths: {}\n")
    assertEquals(listOf(msg("shape.version.key.missing")), problems())
  }

  fun testOtherYamlIsLeftAlone() {
    myFixture.configureByText("application.yaml", "spring:\n  application:\n    name: app\npaths: x\n")
    assertEmpty(problems())
    myFixture.configureByText("deploy.yaml", "apiVersion: v1\nkind: Service\nmetadata:\n  name: x\n---\ninfo: a\npaths: b\n")
    assertEmpty(problems())
  }

  fun testVersionsAndTopLevelKeys() {
    myFixture.configureByText("ok.yaml", "openapi: 3.0\ninfo: {}\npaths: {}\nx-internal: true\ncomponents: {}\n")
    assertEmpty(problems())
    myFixture.configureByText("bad.yaml", "openapi: 2.0\ninfo: {}\npaths: {}\ndefinitions: {}\n")
    assertEquals(listOf(
      msg("shape.unknown.key", "definitions", "openapi"),
      msg("shape.version.value", "openapi", "2.0"),
    ).sorted(), problems())
    myFixture.configureByText("swagger.json", """{ "swagger": "2.0", "info": {}, "paths": {}, "definitions": {}, "components": {} }""")
    assertEquals(listOf(msg("shape.unknown.key", "components", "swagger")), problems())
  }
}
