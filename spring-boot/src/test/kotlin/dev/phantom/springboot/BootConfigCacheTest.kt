// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springboot

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.springboot.core.BootConfigImports
import dev.phantom.springboot.core.BootConfigUsages
import dev.phantom.springboot.core.FunctionBeans
import dev.phantom.springboot.core.ProjectConfigKeys

/**
 * The configuration key gutter in application.yml, and the project-wide caches behind it: the code usages follow Java
 * edits only, the configuration keys and imports follow configuration files only.
 */
class BootConfigCacheTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  override fun setUp() {
    super.setUp()
    myFixture.addClass("package org.springframework.boot; public class SpringApplication {}")
    myFixture.addClass("""
      package org.springframework.beans.factory.annotation;
      @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
      public @interface Value { String value(); }
    """.trimIndent())
  }

  private fun usedInCode(): List<String> = myFixture.findAllGutters().mapNotNull { it.tooltipText }.filter { "Used in code" in it }.sorted()

  private fun keys(): Set<String> = ProjectConfigKeys.keys(project).keys

  private fun imports(): List<String> = BootConfigImports.importedFiles(project).map { it.name }.sorted()

  private fun edit(file: PsiFile, from: String, to: String) {
    val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
    WriteCommandAction.runWriteCommandAction(project) {
      val at = document.text.indexOf(from)
      document.replaceString(at, at + from.length, to)
    }
    PsiDocumentManager.getInstance(project).commitAllDocuments()
  }

  private fun javaValue(key: String): PsiFile = myFixture.addFileToProject("app/Jwt.java", """
    package app;
    public class Jwt { @org.springframework.beans.factory.annotation.Value("${'$'}{$key}") String secret; }
  """.trimIndent())

  fun testYamlKeyShowsGutterToItsValueUsage() {
    javaValue("app.secret")
    myFixture.configureByText("application.yml", "app:\n  secret: s\n  unused: u\n")
    assertEquals(listOf("Used in code: app.secret"), usedInCode())
  }

  fun testTypingInYamlDoesNotRecomputeCodeUsages() {
    javaValue("app.secret")
    myFixture.configureByText("application.yml", "app:\n  secret: s\n  <caret>\n")
    assertEquals(listOf("Used in code: app.secret"), usedInCode())
    val before = BootConfigUsages.computeCount.get()
    myFixture.type("other: o")
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    assertEquals(listOf("Used in code: app.secret"), usedInCode())
    assertEquals(before, BootConfigUsages.computeCount.get())
  }

  fun testEditingJavaMovesTheUsage() {
    val java = javaValue("app.secret")
    myFixture.configureByText("application.yml", "app:\n  secret: s\n  other: o\n")
    assertEquals(listOf("Used in code: app.secret"), usedInCode())
    edit(java, "app.secret", "app.other")
    assertEquals(listOf("Used in code: app.other"), usedInCode())
    edit(java, "@org.springframework.beans.factory.annotation.Value(\"\${app.other}\")", "")
    assertEquals(emptyList<String>(), usedInCode())
  }

  fun testTypingInJavaDoesNotReReadConfigFiles() {
    myFixture.addFileToProject("application.yml", "app:\n  name: a\nspring:\n  config:\n    import: classpath:extra.yml\n")
    myFixture.addFileToProject("extra.yml", "extra:\n  key: k\n")
    myFixture.configureByText("Service.java", "package app;\npublic class Service { <caret> }\n")
    assertTrue("app.name" in keys())
    assertEquals(listOf("extra.yml"), imports())
    val keysBefore = ProjectConfigKeys.computeCount.get()
    val importsBefore = BootConfigImports.computeCount.get()
    myFixture.type("int count;")
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    assertTrue("extra.key" in keys())
    assertEquals(listOf("extra.yml"), imports())
    assertEquals(keysBefore, ProjectConfigKeys.computeCount.get())
    assertEquals(importsBefore, BootConfigImports.computeCount.get())
  }

  fun testUnsavedYamlEditUpdatesKeys() {
    myFixture.configureByText("application.yml", "app:\n  name: a\n  <caret>\n")
    assertTrue("app.name" in keys())
    myFixture.type("title: t")
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    assertTrue("app.title" in keys())
  }

  fun testExternalChangeUpdatesKeysAndImports() {
    val base = myFixture.addFileToProject("application.properties", "app.name=a\n").virtualFile
    myFixture.addFileToProject("extra.properties", "extra.key=k\n")
    assertEquals(setOf("app.name"), keys())
    assertEquals(emptyList<String>(), imports())
    WriteAction.run<Throwable> { VfsUtil.saveText(base, "app.title=t\nspring.config.import=classpath:extra.properties\n") }
    assertEquals(listOf("extra.properties"), imports())
    assertEquals(setOf("app.title", "spring.config.import", "extra.key"), keys())
  }

  fun testCreatedAndDeletedConfigFileUpdatesKeys() {
    myFixture.addFileToProject("application.yml", "app:\n  name: a\n")
    assertEquals(setOf("app.name"), keys())
    val dev = myFixture.addFileToProject("application-dev.yml", "app:\n  dev: d\n").virtualFile
    assertEquals(setOf("app.name", "app.dev"), keys())
    WriteAction.run<Throwable> { dev.delete(this) }
    assertEquals(setOf("app.name"), keys())
  }

  /** Function bean names come from Java: a Java edit updates them, typing in the YAML file that names them does not. */
  fun testFunctionBeanNamesFollowJavaEditsOnly() {
    myFixture.addClass("package org.springframework.context.annotation; public @interface Bean { String[] name() default {}; String[] value() default {}; }")
    val functions = myFixture.addFileToProject("app/Functions.java", """
      package app;
      import java.util.function.Function;
      public class Functions {
        @org.springframework.context.annotation.Bean public Function<String, String> upper() { return s -> s; }
      }
    """.trimIndent())
    val yaml = myFixture.addFileToProject("application.yml", "spring:\n  cloud:\n    function:\n      definition: upper\n")
    val first = FunctionBeans.names(project)
    assertEquals(setOf("upper"), first)
    edit(yaml, "definition: upper", "definition: uppe")
    assertSame(first, FunctionBeans.names(project))
    edit(functions, "upper()", "lower()")
    assertEquals(setOf("lower"), FunctionBeans.names(project))
  }
}
