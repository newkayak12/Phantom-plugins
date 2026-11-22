// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springboot

import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.psi.PsiField
import com.intellij.psi.PsiNamedElement
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.springboot.core.BootConfigDocumentationProvider
import dev.phantom.springboot.core.BootConfigGotoDeclarationHandler
import dev.phantom.springboot.core.BootConfigKeyInspection
import dev.phantom.springboot.core.ConfigKeyDocElement
import dev.phantom.springboot.core.PhantomSpringBootBundle
import dev.phantom.springboot.yaml.BootYamlDocumentationProvider

/** Keys of the project's own @ConfigurationProperties classes, navigation from keys, and the key inspection. */
class BootSourcePropertiesTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  companion object {

    private val METADATA = """
      {
        "properties": [
          {"name": "server.port", "type": "java.lang.Integer", "sourceType": "org.springframework.boot.autoconfigure.web.ServerProperties"},
          {"name": "spring.jpa.show-sql", "type": "java.lang.Boolean", "deprecation": {"level": "warning", "replacement": "spring.jpa.properties.hibernate.show_sql"}},
          {"name": "spring.old-name", "type": "java.lang.String", "deprecation": {"replacement": "spring.new-name"}},
          {"name": "spring.new-name", "type": "java.lang.String"}
        ]
      }
    """.trimIndent()
  }

  override fun setUp() {
    super.setUp()
    myFixture.addClass("package org.springframework.boot; public class SpringApplication {}")
    myFixture.addClass("""
      package org.springframework.boot.context.properties;
      @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
      public @interface ConfigurationProperties { String value() default ""; String prefix() default ""; }
    """.trimIndent())
    myFixture.addClass("package org.springframework.boot.autoconfigure.web; public class ServerProperties { private Integer port; public void setPort(Integer p) {} }")
    myFixture.addClass("""
      package app;
      @org.springframework.boot.context.properties.ConfigurationProperties(prefix = "app.mail")
      public class MailProperties {
        /** SMTP host to connect to. Used for every message. */
        private String host = "localhost";
        private int port;
        private Mode mode;
        private final Retry retry = new Retry();
        public void setHost(String host) {}
        public void setPort(int port) {}
        public void setMode(Mode mode) {}
        public Retry getRetry() { return retry; }
        public static class Retry { private int maxAttempts; public void setMaxAttempts(int maxAttempts) {} }
        public enum Mode { FAST, SAFE }
      }
    """.trimIndent())
    val metadata = myFixture.tempDirFixture.createFile("bootlib/META-INF/spring-configuration-metadata.json", METADATA)
    ModuleRootModificationUtil.addModuleLibrary(module, metadata.parent.parent.url)
  }

  override fun tearDown() {
    try {
      assertLayeredKeysMatchFlat(module)
    }
    finally {
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
  }

  private fun complete(fileName: String, text: String): List<String> {
    myFixture.configureByText(fileName, text)
    myFixture.completeBasic()
    return myFixture.lookupElementStrings ?: emptyList()
  }

  fun testKeysFromSourceWithoutGeneratedMetadata() {
    assertSameElements(complete("application.yml", "app:\n  mail:\n    <caret>"), "host", "port", "mode", "retry")
    assertSameElements(complete("application.yml", "app:\n  mail:\n    retry:\n      <caret>"), "max-attempts")
    assertSameElements(complete("application.yml", "app:\n  mail:\n    mode: <caret>"), "fast", "safe")
    assertContainsElements(complete("application.properties", "app.mail.<caret>"), "app.mail.host", "app.mail.retry.max-attempts")
  }

  fun testDocumentationFromJavadocAndInitializer() {
    myFixture.configureByText("application.yml", "app:\n  mail:\n    ho<caret>st: x")
    val provider = BootYamlDocumentationProvider()
    val element = provider.getCustomDocumentationElement(myFixture.editor, myFixture.file, myFixture.file.findElementAt(myFixture.caretOffset), myFixture.caretOffset)
    val doc = BootConfigDocumentationProvider.render((element as ConfigKeyDocElement).lookup)
    assertTrue(doc, doc.contains("SMTP host to connect to."))
    assertTrue(doc, doc.contains("localhost"))
  }

  private fun gotoTarget(): PsiNamedElement? {
    val offset = myFixture.caretOffset
    val targets = BootConfigGotoDeclarationHandler().getGotoDeclarationTargets(myFixture.file.findElementAt(offset), offset, myFixture.editor)
    return targets?.singleOrNull() as? PsiNamedElement
  }

  fun testGotoDeclaration() {
    myFixture.configureByText("application.yml", "app:\n  mail:\n    ho<caret>st: x")
    assertEquals("host", (gotoTarget() as PsiField).name)
    myFixture.configureByText("application.yml", "app:\n  mail:\n    retry:\n      max-att<caret>empts: 3")
    assertEquals("maxAttempts", gotoTarget()?.name)
    myFixture.configureByText("application.yml", "app:\n  ma<caret>il:\n    host: x")
    assertEquals("MailProperties", gotoTarget()?.name)
    myFixture.configureByText("application.properties", "server.po<caret>rt=8080")
    assertEquals("port", gotoTarget()?.name)
  }

  fun testDeprecatedAndUnknownKeys() {
    myFixture.enableInspections(BootConfigKeyInspection())
    val deprecated = PhantomSpringBootBundle.message("inspection.key.deprecated", "spring.jpa.show-sql") + " " +
                     PhantomSpringBootBundle.message("doc.replacement", "spring.jpa.properties.hibernate.show_sql")
    val unknown = PhantomSpringBootBundle.message("inspection.key.unknown", "app.mail.hots", "app.mail")
    myFixture.configureByText("application.yml", """
      spring:
        jpa:
          <warning descr="$deprecated">show-sql</warning>: true
      app:
        mail:
          host: x
          <warning descr="$unknown">hots</warning>: y
      logging:
        level:
          org.example: debug
    """.trimIndent())
    myFixture.checkHighlighting()
  }

  fun testReplaceDeprecatedKey() {
    myFixture.enableInspections(BootConfigKeyInspection())
    myFixture.configureByText("application.yml", "spring:\n  old-<caret>name: x\n")
    myFixture.launchAction(myFixture.findSingleIntention(PhantomSpringBootBundle.message("inspection.key.replace", "new-name")))
    myFixture.checkResult("spring:\n  new-name: x\n")
    myFixture.configureByText("application.properties", "spring.jpa.show-<caret>sql=true\n")
    myFixture.launchAction(myFixture.findSingleIntention(PhantomSpringBootBundle.message("inspection.key.replace", "spring.jpa.properties.hibernate.show_sql")))
    myFixture.checkResult("spring.jpa.properties.hibernate.show_sql=true\n")
  }
}
