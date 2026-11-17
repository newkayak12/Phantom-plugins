// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springboot

import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.springboot.core.BootConfigDocumentationProvider
import dev.phantom.springboot.core.ConfigKeyDocElement
import dev.phantom.springboot.core.ConfigNames
import dev.phantom.springboot.yaml.BootYamlDocumentationProvider

class BootConfigCompletionTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  companion object {

    // Same shape as META-INF/spring-configuration-metadata.json in spring-boot-autoconfigure.
    private val METADATA = """
      {
        "groups": [
          {"name": "spring.jpa", "type": "org.springframework.boot.autoconfigure.orm.jpa.JpaProperties", "description": "JPA settings."}
        ],
        "properties": [
          {"name": "server.port", "type": "java.lang.Integer", "description": "Server HTTP port.", "defaultValue": 8080,
           "sourceType": "org.springframework.boot.autoconfigure.web.ServerProperties"},
          {"name": "server.shutdown", "type": "org.springframework.boot.web.server.Shutdown", "description": "Type of shutdown."},
          {"name": "spring.datasource.url", "type": "java.lang.String", "description": "JDBC URL of the database."},
          {"name": "spring.jpa.open-in-view", "type": "java.lang.Boolean", "description": "Register OpenEntityManagerInViewInterceptor.", "defaultValue": true},
          {"name": "spring.jpa.hibernate.ddl-auto", "type": "java.lang.String", "description": "DDL mode."},
          {"name": "spring.jpa.show-sql", "type": "java.lang.Boolean", "deprecation": {"level": "warning", "replacement": "spring.jpa.properties.hibernate.show_sql"}},
          {"name": "logging.level", "type": "java.util.Map<java.lang.String,java.lang.String>", "description": "Log levels severity mapping."}
        ],
        "hints": [
          {"name": "spring.jpa.hibernate.ddl-auto", "values": [{"value": "none", "description": "Disable DDL handling."}, {"value": "validate"}, {"value": "update"}]},
          {"name": "logging.level.values", "values": [{"value": "info"}, {"value": "debug"}]}
        ]
      }
    """.trimIndent()
  }

  override fun setUp() {
    super.setUp()
    myFixture.addClass("package org.springframework.boot; public class SpringApplication {}")
    myFixture.addClass("package org.springframework.boot.web.server; public enum Shutdown { GRACEFUL, IMMEDIATE }")
    val metadata = myFixture.tempDirFixture.createFile("bootlib/META-INF/spring-configuration-metadata.json", METADATA)
    ModuleRootModificationUtil.addModuleLibrary(module, metadata.parent.parent.url)
  }

  override fun tearDown() {
    try {
      ModuleRootModificationUtil.updateModel(module) { model ->
        model.moduleLibraryTable.libraries.toList().forEach { model.moduleLibraryTable.removeLibrary(it) }
      }
    }
    catch (e: Throwable) {
      addSuppressedException(e)
    }
    finally {
      super.tearDown()
    }
  }

  private fun complete(fileName: String, text: String): List<String> {
    myFixture.configureByText(fileName, text)
    myFixture.completeBasic()
    return myFixture.lookupElementStrings ?: emptyList()
  }

  fun testYamlRootKeys() {
    assertSameElements(complete("application.yml", "<caret>"), "server", "spring", "logging")
  }

  fun testYamlNestedGroupInsertsMappingAndIndent() {
    myFixture.configureByText("application.yml", "spring:\n  jp<caret>")
    myFixture.completeBasic()
    myFixture.checkResult("spring:\n  jpa:\n    <caret>")
  }

  fun testYamlLeafInsertsColon() {
    myFixture.configureByText("application.yaml", "server:\n  po<caret>")
    myFixture.completeBasic()
    myFixture.checkResult("server:\n  port: <caret>")
  }

  fun testYamlChildrenOfGroup() {
    assertSameElements(complete("application-test.yaml", "spring:\n  jpa:\n    <caret>"), "open-in-view", "hibernate", "show-sql")
  }

  fun testYamlDeepKeyFoundByLastSegment() {
    myFixture.configureByText("application.yml", "spring:\n  ddl<caret>")
    myFixture.completeBasic()
    myFixture.checkResult("spring:\n  jpa.hibernate.ddl-auto: <caret>")
  }

  fun testYamlFourSpaceIndentIsKept() {
    myFixture.configureByText("application.yml", "server:\n    port: 1\nspring:\n    jp<caret>")
    myFixture.completeBasic()
    myFixture.checkResult("server:\n    port: 1\nspring:\n    jpa:\n        <caret>")
  }

  fun testYamlValuesFromHintsBooleansEnumsAndMaps() {
    assertSameElements(complete("application.yml", "spring:\n  jpa:\n    hibernate:\n      ddl-auto: <caret>"), "none", "validate", "update")
    assertSameElements(complete("application.yml", "spring:\n  jpa:\n    open-in-view: <caret>"), "true", "false")
    assertSameElements(complete("application.yml", "server:\n  shutdown: <caret>"), "graceful", "immediate")
    assertSameElements(complete("application.yml", "logging:\n  level:\n    org.hibernate: <caret>"), "info", "debug")
  }

  fun testYamlNotOfferedInOtherFilesOrLists() {
    assertEmpty(complete("docker-compose.yml", "ser<caret>"))
    assertEmpty(complete("application.yml", "spring:\n  profiles:\n    - <caret>"))
  }

  fun testPropertiesKeysAndValues() {
    myFixture.configureByText("application.properties", "spring.jpa.op<caret>")
    myFixture.completeBasic()
    myFixture.checkResult("spring.jpa.open-in-view=<caret>")
    assertSameElements(complete("application.properties", "spring.jpa.open-in-view=<caret>"), "true", "false")
    assertContainsElements(complete("application-local.properties", "server.<caret>"), "server.port", "server.shutdown")
  }

  fun testDocumentationForKeyUnderCaret() {
    myFixture.configureByText("application.yml", "server:\n  po<caret>rt: 8080")
    val provider = BootYamlDocumentationProvider()
    val element = provider.getCustomDocumentationElement(myFixture.editor, myFixture.file, myFixture.file.findElementAt(myFixture.caretOffset), myFixture.caretOffset)
    assertInstanceOf(element, ConfigKeyDocElement::class.java)
    val doc = provider.generateDoc(element, null)!!
    assertTrue(doc, doc.contains("server.port") && doc.contains("Server HTTP port.") && doc.contains("java.lang.Integer") && doc.contains("8080"))
  }

  fun testDeprecatedKeyDocumentationShowsReplacement() {
    myFixture.configureByText("application.yml", "spring:\n  jpa:\n    sh<caret>ow-sql: true")
    val provider = BootYamlDocumentationProvider()
    val element = provider.getCustomDocumentationElement(myFixture.editor, myFixture.file, myFixture.file.findElementAt(myFixture.caretOffset), myFixture.caretOffset)
    val doc = BootConfigDocumentationProvider.render((element as ConfigKeyDocElement).lookup)
    assertTrue(doc, doc.contains("spring.jpa.properties.hibernate.show_sql"))
  }

  fun testRelaxedBindingNames() {
    assertEquals("spring.jpa.open-in-view", ConfigNames.normalize("spring.jpa.openInView"))
    assertEquals("spring.jpa.open-in-view", ConfigNames.normalize("spring.jpa.open_in_view"))
    assertEquals("spring.datasource.url", ConfigNames.normalize("SPRING.DATASOURCE.URL"))
    assertEquals("server.servlet.context-path", ConfigNames.normalize("server.servlet.contextPath"))
    assertEquals("my.list.name", ConfigNames.normalize("my.list[0].name"))
  }
}
