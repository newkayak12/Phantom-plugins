// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springboot

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.springboot.core.ProfileFiles
import com.intellij.openapi.util.registry.Registry
import dev.phantom.springboot.core.BootConfigKeyInspection
import dev.phantom.springboot.core.GhostMode
import com.intellij.openapi.util.Disposer

/** Profile files and the base file they override, and the @SpringBootApplication configuration gutter. */
class BootProfileMarkersTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  override fun setUp() {
    super.setUp()
    myFixture.addClass("package org.springframework.boot; public class SpringApplication {}")
    myFixture.addClass("""
      package org.springframework.boot.autoconfigure;
      @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
      public @interface SpringBootApplication {}
    """.trimIndent())
  }

  /** Tooltip -> where each target is: `file:line text`. */
  private fun gutters(filter: (String) -> Boolean): List<String> = myFixture.findAllGutters().mapNotNull { g ->
    val tooltip = g.tooltipText ?: return@mapNotNull null
    if (!filter(tooltip)) return@mapNotNull null
    val info = (g as? LineMarkerInfo.LineMarkerGutterIconRenderer<*>)?.lineMarkerInfo as? RelatedItemLineMarkerInfo<*> ?: return@mapNotNull null
    "$tooltip -> " + info.createGotoRelatedItems().mapNotNull { item ->
      when (val e = item.element) {
        is PsiFile -> e.name
        null -> null
        else -> "${e.containingFile.name}:" + e.containingFile.text.substring(e.textOffset).substringBefore('\n').trim()
      }
    }.sorted().joinToString()
  }.sorted()

  private fun profile(t: String) = "Overrides" in t || "Overridden" in t

  private val base = """
    server:
      port: 8080
    app:
      greeting: hello
      only-base: x
  """.trimIndent()

  fun testProfileFileOverridesBase() {
    myFixture.addFileToProject("application.yml", base)
    myFixture.addFileToProject("application-prod.properties", "server.port=80\n")
    myFixture.configureByText("application-dev.yml", """
      server:
        port: 9090
      app:
        greeting: hi
        only-dev: y
    """.trimIndent())
    assertEquals(listOf(
      "Overrides application.yml: 8080 -> application.yml:port: 8080",
      "Overrides application.yml: hello -> application.yml:greeting: hello",
    ), gutters(::profile))
  }

  fun testBaseShowsProfileOverrides() {
    myFixture.addFileToProject("application-dev.yml", "server:\n  port: 9090\n")
    myFixture.addFileToProject("application-prod.properties", "server.port=80\napp.greeting=yo\n")
    // a profile file of bootstrap.yml is not one of application.yml
    myFixture.addFileToProject("bootstrap-dev.yml", "app:\n  only-base: z\n")
    myFixture.configureByText("application.yml", base)
    assertEquals(listOf(
      "Overridden in application-dev.yml, application-prod.properties -> application-dev.yml:port: 9090, application-prod.properties:server.port=80",
      "Overridden in application-prod.properties -> application-prod.properties:app.greeting=yo",
    ), gutters(::profile))
  }

  /** The counterpart files are read again after a configuration change, not after a Java keystroke. */
  fun testCounterpartKeysFollowConfigurationOnly() {
    val baseFile = myFixture.addFileToProject("application.properties", "server.port=8080\n").virtualFile
    val java = myFixture.addFileToProject("app/Service.java", "package app;\npublic class Service { int a; }\n")
    myFixture.configureByText("application-dev.properties", "server.port=9090\n")
    assertEquals(listOf("Overrides application.properties: 8080 -> application.properties:server.port=8080"), gutters(::profile))
    val reads = ProfileFiles.readCount.get()
    val document = PsiDocumentManager.getInstance(project).getDocument(java)!!
    WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.text.indexOf("int a;"), "int b; ") }
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    gutters(::profile)
    assertEquals(reads, ProfileFiles.readCount.get())
    WriteAction.run<Throwable> { VfsUtil.saveText(baseFile, "server.port=8081\n") }
    assertEquals(listOf("Overrides application.properties: 8081 -> application.properties:server.port=8081"), gutters(::profile))
  }

  fun testPropertiesProfileFile() {
    myFixture.addFileToProject("application.properties", "server.port=8080\nspring.application.name=app\n")
    myFixture.configureByText("application-dev.properties", "server.port=9090\nlogging.level.root=debug\n")
    assertEquals(listOf("Overrides application.properties: 8080 -> application.properties:server.port=8080"), gutters(::profile))
  }

  fun testApplicationClassListsConfigFiles() {
    myFixture.addFileToProject("application.yml", "spring:\n  config:\n    import: classpath:kafka/index.yaml\n")
    myFixture.addFileToProject("kafka/index.yaml", "kafka:\n  topic: t\n")
    myFixture.addFileToProject("application-dev.yml", "x: 1\n")
    myFixture.configureByText("App.java", """
      package app;
      @org.springframework.boot.autoconfigure.SpringBootApplication
      public class App {}
    """.trimIndent())
    assertEquals(listOf("Configuration: application-dev.yml, application.yml, index.yaml -> application-dev.yml, application.yml, index.yaml"),
                 gutters { it.startsWith("Configuration:") }.map { it.replace(Regex("Configuration: (.*) ->")) { m -> "Configuration: " + m.groupValues[1].split(", ").sorted().joinToString() + " ->" } })
  }

  fun testGhostModeHidesGuttersAndInspections() {
    ghostMode(true)
    myFixture.addFileToProject("application.yml", base)
    myFixture.configureByText("application-dev.yml", "server:\n  port: 9090\n")
    assertEmpty(gutters(::profile))
    assertFalse(BootConfigKeyInspection().isAvailableForFile(myFixture.file))
    ghostMode(false)
    assertTrue(BootConfigKeyInspection().isAvailableForFile(myFixture.file))
  }

  // Phantom registers phantom.ghost; the IntelliJ test platform does not, so a system property, which RegistryValue reads before
  // the bundle, makes the key resolvable for setValue.
  private fun ghostMode(on: Boolean) {
    System.setProperty(GhostMode.KEY, "false")
    Disposer.register(testRootDisposable) { System.clearProperty(GhostMode.KEY) }
    Registry.get(GhostMode.KEY).setValue(on, testRootDisposable)
  }
}
