// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springboot

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

/** Gutters on @ConditionalOnProperty, @EnableConfigurationProperties, test properties, properties fields and profile values. */
class BootCodeMarkersTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  override fun setUp() {
    super.setUp()
    val runtime = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)"
    myFixture.addClass("package org.springframework.boot; public class SpringApplication {}")
    myFixture.addClass("package org.springframework.boot.context.properties; $runtime public @interface ConfigurationProperties { String value() default \"\"; String prefix() default \"\"; }")
    myFixture.addClass("package org.springframework.boot.context.properties; $runtime public @interface EnableConfigurationProperties { Class<?>[] value() default {}; }")
    myFixture.addClass("""
      package org.springframework.boot.autoconfigure.condition;
      $runtime public @interface ConditionalOnProperty {
        String[] value() default {}; String prefix() default ""; String[] name() default {}; String havingValue() default ""; boolean matchIfMissing() default false;
      }
    """.trimIndent())
    myFixture.addClass("package org.springframework.boot.test.context; $runtime public @interface SpringBootTest { String[] properties() default {}; }")
    myFixture.addClass("package org.springframework.test.context; $runtime public @interface TestPropertySource { String[] properties() default {}; }")
    myFixture.addFileToProject("application.yml", """
      app:
        cache:
          enabled: true
        mail:
          host: smtp.local
          retry:
            max-attempts: 3
      spring:
        profiles:
          active: dev,stage
          group:
            prod:
              - cloud
              - mysql
    """.trimIndent())
    myFixture.addFileToProject("application-dev.yml", "app:\n  mail:\n    host: dev\n")
    myFixture.addFileToProject("application-cloud.properties", "x=1\n")
    myFixture.addFileToProject("application-mysql.yml", "y: 2\n")
  }

  /** Tooltip -> where each target is (`file:line text`, a file name, or a class name). */
  private fun gutters(filter: (String) -> Boolean): List<String> = myFixture.findAllGutters().mapNotNull { g ->
    val tooltip = g.tooltipText ?: return@mapNotNull null
    if (!filter(tooltip)) return@mapNotNull null
    val info = (g as? LineMarkerInfo.LineMarkerGutterIconRenderer<*>)?.lineMarkerInfo as? RelatedItemLineMarkerInfo<*> ?: return@mapNotNull null
    "$tooltip -> " + info.createGotoRelatedItems().mapNotNull { item ->
      when (val e = item.element) {
        is PsiFile -> e.name
        is PsiNamedElement -> e.name
        null -> null
        else -> "${e.containingFile.name}:" + e.containingFile.text.substring(e.textOffset).substringBefore('\n').trim()
      }
    }.sorted().joinToString()
  }.sorted()

  fun testConditionalOnProperty() {
    myFixture.configureByText("CacheConfig.java", """
      package app;
      import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
      @ConditionalOnProperty(prefix = "app.cache", name = "enabled", havingValue = "true")
      class CacheConfig {
        @ConditionalOnProperty(name = "app.feature.x", matchIfMissing = true)
        Object feature() { return null; }
      }
    """.trimIndent())
    assertEquals(listOf(
      "app.cache.enabled = true (application.yml) -> application.yml:enabled: true",
      "app.feature.x is not set: the condition matches when it is missing -> ",
    ), gutters { "app." in it })
  }

  fun testEnableConfigurationPropertiesAndFields() {
    myFixture.addClass("""
      package app;
      @org.springframework.boot.context.properties.ConfigurationProperties("app.mail")
      public class MailProperties {
        private String host;
        private Retry retry;
        private String unset;
        public static class Retry { private int maxAttempts; }
      }
    """.trimIndent())
    myFixture.configureByText("MailConfig.java", """
      package app;
      @org.springframework.boot.context.properties.EnableConfigurationProperties(MailProperties.class)
      class MailConfig {}
    """.trimIndent())
    assertEquals(listOf("Registers MailProperties -> MailProperties"), gutters { it.startsWith("Registers") })
    myFixture.configureFromExistingVirtualFile(myFixture.findClass("app.MailProperties").containingFile.virtualFile)
    assertEquals(listOf(
      "Keys set under app.mail -> application-dev.yml:host: dev, application.yml:host: smtp.local, application.yml:max-attempts: 3",
      "Keys set under app.mail.retry: 1 -> application.yml:max-attempts: 3",
      "app.mail.host = smtp.local (application.yml) -> application-dev.yml:host: dev, application.yml:host: smtp.local",
    ), gutters { "app.mail" in it })
  }

  fun testKotlinPropertiesAndEnable() {
    myFixture.addFileToProject("app/Mail.kt", """
      package app
      import org.springframework.boot.context.properties.ConfigurationProperties
      @ConfigurationProperties(prefix = "app.mail")
      class KMail(
          val host: String,
          val other: String,
      )
    """.trimIndent())
    myFixture.configureFromTempProjectFile("app/Mail.kt")
    assertEquals(listOf(
      "Keys set under app.mail -> application-dev.yml:host: dev, application.yml:host: smtp.local, application.yml:max-attempts: 3",
      "app.mail.host = smtp.local (application.yml) -> application-dev.yml:host: dev, application.yml:host: smtp.local",
    ), gutters { "app.mail" in it })
    myFixture.configureByText("Enable.kt", """
      package app
      @org.springframework.boot.context.properties.EnableConfigurationProperties(KMail::class)
      class Enable
    """.trimIndent())
    assertEquals(listOf("Registers KMail -> KMail"), gutters { it.startsWith("Registers") })
  }

  fun testTestProperties() {
    myFixture.configureByText("MailTest.java", """
      package app;
      @org.springframework.boot.test.context.SpringBootTest(properties = {"app.mail.host=test", "only.in.test=1"})
      class MailTest {}
    """.trimIndent())
    assertEquals(listOf("app.mail.host = test in this test (configured: smtp.local) -> application-dev.yml:host: dev, application.yml:host: smtp.local"),
                 gutters { "in this test" in it })
  }

  fun testProfileValues() {
    myFixture.configureFromTempProjectFile("application.yml")
    assertEquals(listOf(
      "Profile files: application-cloud.properties, application-mysql.yml -> application-cloud.properties, application-mysql.yml",
      "Profile files: application-dev.yml -> application-dev.yml",
    ), gutters { it.startsWith("Profile files") })
    myFixture.configureByText("application-it.properties", "spring.config.activate.on-profile=dev & !cloud\nspring.profiles.include=nothing\n")
    assertEquals(listOf("Profile files: application-cloud.properties, application-dev.yml -> application-cloud.properties, application-dev.yml"),
                 gutters { it.startsWith("Profile files") })
  }
}
