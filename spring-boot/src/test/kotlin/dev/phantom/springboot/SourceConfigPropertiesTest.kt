// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springboot

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.springboot.core.SourceConfigProperties

/** Keys read from the project's own @ConfigurationProperties classes: when they are read again, and which members bind. */
class SourceConfigPropertiesTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  override fun setUp() {
    super.setUp()
    myFixture.addClass("package org.springframework.boot; public class SpringApplication {}")
    myFixture.addClass("""
      package org.springframework.boot.context.properties;
      @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
      public @interface ConfigurationProperties { String value() default ""; String prefix() default ""; }
    """.trimIndent())
  }

  private fun keys(): List<String> = SourceConfigProperties.metadata(module).properties.map { it.name }.sorted()

  private fun edit(file: PsiFile, from: String, to: String) {
    val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
    WriteCommandAction.runWriteCommandAction(project) {
      val at = document.text.indexOf(from)
      document.replaceString(at, at + from.length, to)
    }
    PsiDocumentManager.getInstance(project).commitAllDocuments()
  }

  fun testTypingInMethodBodyDoesNotReadKeysAgain() {
    val file = myFixture.addFileToProject("app/Mail.java", """
      package app;
      @org.springframework.boot.context.properties.ConfigurationProperties(prefix = "app")
      public class Mail {
        private String host;
        public void setHost(String host) { int x = 1; }
      }
    """.trimIndent())
    assertEquals(listOf("app.host"), keys())
    val before = SourceConfigProperties.computeCount.get()
    edit(file, "int x = 1;", "int x = 2; x++;")
    assertEquals(listOf("app.host"), keys())
    assertEquals(before, SourceConfigProperties.computeCount.get())
    edit(file, "private String host;", "private String host;\n  private int port;\n  public void setPort(int port) {}")
    assertEquals(listOf("app.host", "app.port"), keys())
  }
}
