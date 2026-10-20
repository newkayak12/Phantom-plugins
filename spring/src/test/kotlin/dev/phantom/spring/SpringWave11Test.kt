// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.spring.core.PhantomSpringBundle
import dev.phantom.spring.core.ide.*
import dev.phantom.spring.security.SecurityRolePrefixInspection

/** Test transactions, contradicting injection annotations, @Required, @Qualifier types and ROLE_ prefixes. */
class SpringWave11Test : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  private val runtime = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)"

  override fun setUp() {
    super.setUp()
    SpringStubs.add(myFixture)
  }

  private fun problems(vararg tools: LocalInspectionTool): List<String> {
    myFixture.enableInspections(*tools)
    return myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).filter { it.inspectionToolId != null }.mapNotNull { it.description }.sorted()
  }

  private fun msg(key: String, vararg params: Any) = PhantomSpringBundle.message(key, *params)

  fun testTestTransactions() {
    listOf(
      "package org.springframework.test.annotation; $runtime public @interface Commit {}",
      "package org.springframework.test.annotation; $runtime public @interface Rollback { boolean value() default true; }",
      "package org.springframework.test.context.transaction; $runtime public @interface BeforeTransaction {}",
      "package org.springframework.boot.test.autoconfigure.orm.jpa; $runtime @org.springframework.test.context.BootstrapWith @org.springframework.transaction.annotation.Transactional public @interface DataJpaTest {}",
    ).forEach { myFixture.addClass(it) }
    myFixture.addClass("package org.springframework.boot.test.context; public enum WebEnvironment { MOCK, RANDOM_PORT }")
    myFixture.configureByText("Tests.java", """
      package app;
      import org.springframework.boot.test.context.SpringBootTest;
      import org.springframework.test.annotation.*;
      import org.springframework.test.context.transaction.BeforeTransaction;
      import org.springframework.transaction.annotation.Transactional;
      import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

      @SpringBootTest class Plain {
        @Commit void a() {}
        @BeforeTransaction void setUp() {}
      }
      @SpringBootTest @Transactional class Tx {
        @Commit @Rollback void both() {}
        @Rollback(false) void ok() {}
      }
      @DataJpaTest class Jpa { @Commit void ok() {} }
      @SpringBootTest class OneMethod { @Transactional @Commit void ok() {} @BeforeTransaction void fine() {} }
      @SpringBootTest(webEnvironment = org.springframework.boot.test.context.WebEnvironment.RANDOM_PORT) @Transactional class Server {}
      @SpringBootTest(webEnvironment = org.springframework.boot.test.context.WebEnvironment.MOCK) @Transactional class Mock {}
      class NoContext { @Commit void ignored() {} }
    """.trimIndent())
    assertEquals(listOf(
      msg("inspection.test.tx.both"),
      msg("inspection.test.tx.hook", "@BeforeTransaction"),
      msg("inspection.test.tx.none", "@Commit"),
      msg("inspection.test.tx.server"),
    ).sorted(), problems(SpringTestTransactionInspection()))
  }

  fun testInjectionConsistency() {
    myFixture.addClass("package jakarta.annotation; $runtime public @interface Resource { String name() default \"\"; }")
    myFixture.configureByText("Svc.java", """
      package app;
      import org.springframework.beans.factory.annotation.*;
      import jakarta.annotation.Resource;
      @org.springframework.stereotype.Service class Svc {
        @Value("${'$'}{a}") @Qualifier("x") String a;
        @Resource @Autowired Foo foo;
        @Value("${'$'}{b}") String b;
        @Autowired @Qualifier("foo") Foo ok;
      }
      class Foo {}
    """.trimIndent())
    assertEquals(listOf(msg("inspection.injection.consistency.message", "@Autowired", "@Resource"),
                        msg("inspection.injection.consistency.message", "@Qualifier", "@Value")).sorted(),
                 problems(SpringInjectionConsistencyInspection()))
  }

  fun testRequired() {
    myFixture.addClass("package org.springframework.beans.factory.annotation; $runtime public @interface Required {}")
    myFixture.configureByText("Svc.java", """
      package app;
      import org.springframework.beans.factory.annotation.Required;
      class Svc {
        @Required public void setName(String n) {}
        @Required public void init() {}
        public void setOther(String o) {}
      }
    """.trimIndent())
    assertEquals(listOf(msg("inspection.required.not.setter"), msg("inspection.required.removed")).sorted(), problems(SpringRequiredInspection()))
  }

  fun testQualifierType() {
    myFixture.configureByText("Beans.java", """
      package app;
      import org.springframework.beans.factory.annotation.*;
      import org.springframework.context.annotation.*;
      interface Clock {}
      class SystemClock implements Clock {}
      class Mailer {}
      @Configuration class Config {
        @Bean Clock clock() { return new SystemClock(); }
        @Bean Mailer mailer() { return new Mailer(); }
      }
      @org.springframework.stereotype.Service class Svc {
        Svc(@Qualifier("clock") Clock a, @Qualifier("mailer") Clock b, @Qualifier("clock") SystemClock c, @Qualifier("unknown") Clock d) {}
      }
    """.trimIndent())
    assertEquals(listOf(msg("inspection.qualifier.type.message", "mailer", "Mailer", "Clock")), problems(SpringQualifierTypeInspection()))
  }

  fun testRolePrefix() {
    myFixture.addClass("package org.springframework.security.core; public interface Authentication {}")
    myFixture.addClass("package org.springframework.security.access.prepost; $runtime public @interface PreAuthorize { String value(); }")
    myFixture.addClass("package org.springframework.security.test.context.support; $runtime public @interface WithMockUser { String[] roles() default {}; }")
    myFixture.addClass("""
      package org.springframework.security.config.annotation.web.configurers;
      public class AuthorizedUrl { public Object hasRole(String r) { return null; } public Object hasAnyRole(String... r) { return null; } }
    """.trimIndent())
    myFixture.configureByText("Security.java", """
      package app;
      import org.springframework.security.access.prepost.PreAuthorize;
      import org.springframework.security.test.context.support.WithMockUser;
      import org.springframework.security.config.annotation.web.configurers.AuthorizedUrl;
      class Security {
        void configure(AuthorizedUrl url) {
          url.hasRole("ROLE_ADMIN");
          url.hasRole("ADMIN");
          url.hasAnyRole("USER", "ROLE_STAFF");
        }
        @PreAuthorize("hasRole('ROLE_ADMIN') or hasAnyRole('USER', 'ROLE_X')") void a() {}
        @PreAuthorize("hasRole('ADMIN')") void b() {}
        @WithMockUser(roles = {"ROLE_A", "B"}) void test() {}
      }
    """.trimIndent())
    assertEquals(listOf(
      msg("security.role.prefix.config", "ROLE_A"),
      msg("security.role.prefix.config", "ROLE_ADMIN"),
      msg("security.role.prefix.config", "ROLE_STAFF"),
      msg("security.role.prefix.expression", "ROLE_ADMIN"),
      msg("security.role.prefix.expression", "ROLE_X"),
    ).sorted(), problems(SecurityRolePrefixInspection()))
  }
}
