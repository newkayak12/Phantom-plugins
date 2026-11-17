// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springboot

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.springboot.core.BootDatasourceDriverInspection

/** The datasource driver check, files pulled in with spring.config.import, and the configuration key gutters. */
class BootWave7Test : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  override fun setUp() {
    super.setUp()
    myFixture.addClass("package org.springframework.boot; public class SpringApplication {}")
    myFixture.addClass("""
      package org.springframework.boot.context.properties;
      @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
      public @interface ConfigurationProperties { String value() default ""; String prefix() default ""; }
    """.trimIndent())
    myFixture.addClass("""
      package org.springframework.beans.factory.annotation;
      @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
      public @interface Value { String value(); }
    """.trimIndent())
    myFixture.enableInspections(BootDatasourceDriverInspection::class.java)
  }

  private fun problems(): List<String> = myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING)
    .filter { it.inspectionToolId == "PhantomBootDatasourceDriver" }
    .map { "${myFixture.file.text.substring(it.startOffset, it.endOffset)}: ${it.description}" }

  fun testDriverMissing() {
    myFixture.configureByText("application.yml", """
      spring:
        datasource:
          url: jdbc:mysql://localhost:3306/app
    """.trimIndent())
    assertEquals(listOf("jdbc:mysql://localhost:3306/app: No JDBC driver for 'mysql' is on the project's classpath: the application fails to start; add com.mysql:mysql-connector-j"),
                 problems())
  }

  fun testDriverPresentOrUrlNotChecked() {
    myFixture.addClass("package org.postgresql; public class Driver {}")
    myFixture.configureByText("application.properties", """
      spring.datasource.url=jdbc:postgresql://localhost/app
      spring.datasource.hikari.jdbc-url=jdbc:tc:mysql:8:///app
    """.trimIndent())
    assertEquals(emptyList<String>(), problems())
    myFixture.configureByText("application-local.yml", "spring:\n  datasource:\n    url: \${DB_URL}\n")
    assertEquals(emptyList<String>(), problems())
  }

  fun testExplicitDriverClass() {
    myFixture.configureByText("application.properties", """
      spring.datasource.url=jdbc:h2:mem:test
      spring.datasource.driver-class-name=com.example.MyDriver
    """.trimIndent())
    assertEquals(listOf("jdbc:h2:mem:test: No JDBC driver for 'h2' is on the project's classpath: the application fails to start; add com.example.MyDriver"),
                 problems())
  }

  fun testImportedConfigFile() {
    myFixture.addFileToProject("application.yml", """
      spring:
        config:
          import:
            - classpath:persistence/datasource.yaml
    """.trimIndent())
    myFixture.addFileToProject("persistence/datasource.yaml", "spring:\n  datasource:\n    url: jdbc:mariadb://db/app\n")
    myFixture.addFileToProject("other/unrelated.yaml", "spring:\n  datasource:\n    url: jdbc:mariadb://db/app\n")
    myFixture.configureFromTempProjectFile("persistence/datasource.yaml")
    assertEquals(1, problems().size)
    myFixture.configureFromTempProjectFile("other/unrelated.yaml")
    assertEquals(emptyList<String>(), problems())
  }

  private fun tooltips(): List<String> = myFixture.findAllGutters().mapNotNull { it.tooltipText }.filter { "Used in code" in it || "Keys set" in it }.sorted()

  fun testConfigKeyGutters() {
    myFixture.addClass("""
      package app;
      @org.springframework.boot.context.properties.ConfigurationProperties(prefix = "app.mail")
      public class MailProperties { private String host; public void setHost(String h) {} }
    """.trimIndent())
    myFixture.addClass("""
      package app;
      public class Jwt { @org.springframework.beans.factory.annotation.Value("${'$'}{app.jwt-secret:none}") String secret; }
    """.trimIndent())
    myFixture.configureByText("application.yml", """
      app:
        mail:
          host: smtp
        jwtSecret: s
        unused: u
    """.trimIndent())
    assertEquals(listOf("Used in code: app.jwt-secret", "Used in code: app.mail.host"), tooltips())
  }

  fun testConfigurationPropertiesGutter() {
    myFixture.addFileToProject("application.properties", "app.mail.host=smtp\napp.mail.port=25\nother.key=1\n")
    myFixture.configureByText("MailProperties.java", """
      package app;
      @org.springframework.boot.context.properties.ConfigurationProperties(prefix = "app.mail")
      public class MailProperties {
        private String host;
      }
    """.trimIndent())
    // the field's own key gutter (BootCodeMarkersTest) sits on its own line
    assertEquals(listOf("Keys set under app.mail"), tooltips())
  }
}
