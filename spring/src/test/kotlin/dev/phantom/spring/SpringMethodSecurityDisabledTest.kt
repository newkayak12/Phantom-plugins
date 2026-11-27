// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.psi.PsiDocumentManager
import dev.phantom.spring.security.MethodSecurityDisabledInspection
import dev.phantom.spring.security.MethodSecuritySwitches

/** Method security annotations without the configuration that turns them on. */
class SpringMethodSecurityDisabledTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  override fun setUp() {
    super.setUp()
    SpringStubs.add(myFixture)
    val runtime = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)"
    val config = "package org.springframework.security.config.annotation.method.configuration;"
    myFixture.addClass("package org.springframework.security.core; public interface Authentication {}")
    myFixture.addClass("package org.springframework.security.access.prepost; $runtime public @interface PreAuthorize { String value(); }")
    myFixture.addClass("package org.springframework.security.access.annotation; $runtime public @interface Secured { String[] value(); }")
    myFixture.addClass("$config $runtime public @interface EnableMethodSecurity { boolean prePostEnabled() default true; boolean securedEnabled() default false; boolean jsr250Enabled() default false; }")
    myFixture.addClass("$config $runtime public @interface EnableGlobalMethodSecurity { boolean prePostEnabled() default false; boolean securedEnabled() default false; boolean jsr250Enabled() default false; }")
    myFixture.enableInspections(MethodSecurityDisabledInspection::class.java)
  }

  private fun warnings(): List<String> =
    myFixture.doHighlighting(HighlightSeverity.WARNING).filter { it.severity == HighlightSeverity.WARNING }
      .mapNotNull(HighlightInfo::getDescription).filterNot { it.startsWith("[") }.sorted()

  private val service = """
    import org.springframework.security.access.prepost.PreAuthorize;
    import org.springframework.security.access.annotation.Secured;
    class AdminService {
      @PreAuthorize("hasRole('ADMIN')") void delete() {}
      @Secured("ROLE_ADMIN") void purge() {}
    }
  """.trimIndent()

  fun testNothingEnabled() {
    myFixture.configureByText("AdminService.java", service)
    assertEquals(listOf(
      "@PreAuthorize has no effect: method security is not enabled (add @EnableMethodSecurity to a configuration class)",
      "@Secured has no effect: method security is not enabled (add @EnableMethodSecurity to a configuration class)",
    ), warnings())
  }

  fun testEnableMethodSecurityDefaults() {
    myFixture.addFileToProject("app/SecurityConfig.kt", """
      package app
      import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
      @EnableMethodSecurity
      class SecurityConfig
    """.trimIndent())
    myFixture.configureByText("AdminService.java", service)
    assertEquals(listOf("@Secured has no effect: method security is enabled without securedEnabled = true"), warnings())
  }

  /** A composed annotation carrying @EnableMethodSecurity counts where it is used, not where it is declared. */
  fun testComposedEnableAnnotation() {
    myFixture.addClass("""
      package app;
      @org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity(securedEnabled = true)
      @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
      public @interface SecureApp {}
    """.trimIndent())
    myFixture.configureByText("AdminService.java", service)
    assertSize(2, warnings())
    myFixture.addClass("package app; @SecureApp class AppConfig {}")
    assertEmpty(warnings())
  }

  /** Typing in a method body keeps the search for the enabling annotations. */
  fun testStateSurvivesBodyEdits() {
    myFixture.addClass("package app; @org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity class SecurityConfig {}")
    myFixture.configureByText("Work.java", "package app; class Work { void m() { <caret> } }")
    val before = MethodSecuritySwitches.state(project)
    assertNotNull(before)
    myFixture.type("int x = 1;")
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    assertSame(before, MethodSecuritySwitches.state(project))
  }

  fun testGlobalMethodSecurityNeedsFlags() {
    myFixture.addClass("""
      package app;
      @org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity(securedEnabled = true, prePostEnabled = true)
      class OldConfig {}
    """.trimIndent())
    myFixture.configureByText("AdminService.java", service)
    assertEmpty(warnings())
  }
}
