// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.spring.core.PhantomSpringBundle
import dev.phantom.spring.security.SecurityBeanReferenceInspection
import dev.phantom.spring.security.SecurityDebugInspection
import dev.phantom.spring.security.SecuritySelfInvocationInspection

/** Security debug mode, secured methods called within their bean, and near-miss bean references in expressions. */
class SpringSecurityChecksTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  override fun setUp() {
    super.setUp()
    SpringStubs.add(myFixture)
    val runtime = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)"
    myFixture.addClass("package org.springframework.security.core; public interface Authentication {}")
    myFixture.addClass("package org.springframework.security.access.prepost; $runtime public @interface PreAuthorize { String value(); }")
    myFixture.addClass("package org.springframework.security.access.annotation; $runtime public @interface Secured { String[] value(); }")
    myFixture.addClass("package org.springframework.security.config.annotation.web.configuration; $runtime public @interface EnableWebSecurity { boolean debug() default false; }")
  }

  private fun problems(vararg tools: LocalInspectionTool): List<String> {
    myFixture.enableInspections(*tools)
    return myFixture.doHighlighting().filter { it.inspectionToolId != null }.mapNotNull { it.description }.sorted()
  }

  private fun msg(key: String, vararg params: Any) = PhantomSpringBundle.message(key, *params)

  fun testDebugMode() {
    myFixture.configureByText("Security.java", """
      package app;
      import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
      @EnableWebSecurity(debug = true) class DebugSecurity {}
      @EnableWebSecurity(debug = false) class QuietSecurity {}
      @EnableWebSecurity class DefaultSecurity {}
    """.trimIndent())
    assertEquals(listOf(msg("security.debug.message")), problems(SecurityDebugInspection()))
  }

  fun testSelfInvocation() {
    myFixture.configureByText("Accounts.java", """
      package app;
      import org.springframework.security.access.prepost.PreAuthorize;
      import org.springframework.security.access.annotation.Secured;
      @org.springframework.stereotype.Service class Accounts {
        @PreAuthorize("hasRole('ADMIN')") void delete(long id) {}
        @Secured("ROLE_ADMIN") void purge() {}
        void cleanup() { delete(1); this.purge(); }
        @PreAuthorize("hasRole('ADMIN')") void deleteAll() { delete(2); }
        void other(Accounts proxy) { proxy.delete(3); }
      }
      class NotABean {
        @PreAuthorize("hasRole('ADMIN')") void delete() {}
        void run() { delete(); }
      }
    """.trimIndent())
    assertEquals(listOf(
      msg("security.self.invocation.message", "@PreAuthorize", "delete"),
      msg("security.self.invocation.message", "@Secured", "purge"),
    ).sorted(), problems(SecuritySelfInvocationInspection()))
  }

  fun testBeanReferences() {
    myFixture.addClass("package app; @org.springframework.stereotype.Component public class AccessGuard { public boolean check(Object o) { return true; } }")
    myFixture.configureByText("Orders.java", """
      package app;
      import org.springframework.security.access.prepost.PreAuthorize;
      class Orders {
        @PreAuthorize("@accessGuard.check(#id)") void ok(long id) {}
        @PreAuthorize("@accesGuard.check(#id)") void typo(long id) {}
        @PreAuthorize("@AccessGuard.check(#id)") void caseOnly(long id) {}
        @PreAuthorize("@libraryBean.check(#id) and hasRole('a@b.com')") void unknown(long id) {}
      }
    """.trimIndent())
    assertEquals(listOf(
      msg("inspection.bean.name.typo.message", "AccessGuard", "accessGuard"),
      msg("inspection.bean.name.typo.message", "accesGuard", "accessGuard"),
    ).sorted(), problems(SecurityBeanReferenceInspection()))
  }
}
