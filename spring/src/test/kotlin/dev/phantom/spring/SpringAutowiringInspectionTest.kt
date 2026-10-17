// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.spring.core.PhantomSpringBundle
import dev.phantom.spring.core.ide.SpringAutowiringInspection

class SpringAutowiringInspectionTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  companion object {
  }

  override fun setUp() {
    super.setUp()
    val retention = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)"
    myFixture.addClass("package org.springframework.stereotype; $retention public @interface Component { String value() default \"\"; }")
    myFixture.addClass("package org.springframework.context.annotation; $retention @org.springframework.stereotype.Component public @interface Configuration {}")
    myFixture.addClass("package org.springframework.context.annotation; $retention public @interface Bean { String[] name() default {}; String[] value() default {}; }")
    myFixture.addClass("package org.springframework.context.annotation; $retention public @interface Primary {}")
    myFixture.addClass("package org.springframework.context.annotation; $retention public @interface Import { Class<?>[] value(); }")
    myFixture.addClass("package org.springframework.context.annotation; $retention public @interface Conditional { Class<?>[] value(); }")
    myFixture.addClass("package org.springframework.context.annotation; $retention @Conditional({}) public @interface Profile { String[] value(); }")
    myFixture.addClass("package org.springframework.beans.factory.annotation; $retention public @interface Qualifier { String value() default \"\"; }")
    myFixture.addClass("package org.springframework.beans.factory.annotation; $retention public @interface Autowired { boolean required() default true; }")
    myFixture.addClass("package org.springframework.beans.factory; public interface ObjectProvider<T> {}")
    myFixture.addClass("package app; public interface Port {}")
    myFixture.enableInspections(SpringAutowiringInspection())
  }

  private fun noBean(type: String) = PhantomSpringBundle.message("inspection.autowiring.no.bean", type)
  private fun multiple(type: String, names: String) = PhantomSpringBundle.message("inspection.autowiring.multiple", type, names)

  fun testImplementationWithoutStereotype() {
    myFixture.addClass("package app; public class Adapter implements Port {}")
    myFixture.configureByText("Svc.java", """
      package app;
      @org.springframework.stereotype.Component
      public class Svc {
        public Svc(Port <warning descr="${noBean("Port")}">port</warning>) {}
      }
    """.trimIndent())
    myFixture.checkHighlighting()
  }

  fun testOptionalInjectionAndGeneratedProxiesAreQuiet() {
    myFixture.addClass("package app; public class Adapter implements Port {}")
    myFixture.addClass("package app; public interface FeignLike {}")
    myFixture.configureByText("Svc.java", """
      package app;
      import org.springframework.beans.factory.annotation.Autowired;
      @org.springframework.stereotype.Component
      public class Svc {
        @Autowired(required = false) Port optional;
        @Autowired FeignLike proxy;
        @Autowired java.util.List<Port> all;
        @Autowired java.util.Set<Port> some;
        @Autowired org.springframework.beans.factory.ObjectProvider<Port> provider;
      }
    """.trimIndent())
    myFixture.checkHighlighting()
  }

  fun testImportedClassIsABean() {
    myFixture.addClass("package app; public class Adapter implements Port {}")
    myFixture.addClass("package app; @org.springframework.context.annotation.Configuration @org.springframework.context.annotation.Import(Adapter.class) public class Config {}")
    myFixture.configureByText("Svc.java", "package app; @org.springframework.stereotype.Component public class Svc { public Svc(Port port) {} }")
    myFixture.checkHighlighting()
  }

  fun testMultipleBeansAndTieBreakers() {
    myFixture.addClass("package app; @org.springframework.stereotype.Component public class FastPort implements Port {}")
    myFixture.addClass("package app; @org.springframework.stereotype.Component public class SlowPort implements Port {}")
    myFixture.configureByText("Svc.java", """
      package app;
      import org.springframework.beans.factory.annotation.Qualifier;
      @org.springframework.stereotype.Component
      public class Svc {
        public Svc(Port <warning descr="${multiple("Port", "fastPort, slowPort")}">port</warning>,
                   Port slowPort,
                   @Qualifier("fastPort") Port chosen) {}
      }
    """.trimIndent())
    myFixture.checkHighlighting()
  }

  fun testQualifierFixAndPrimaryFix() {
    myFixture.addClass("package app; @org.springframework.stereotype.Component public class FastPort implements Port {}")
    val slow = myFixture.addClass("package app; @org.springframework.stereotype.Component public class SlowPort implements Port {}")
    myFixture.configureByText("Svc.java", "package app; @org.springframework.stereotype.Component public class Svc { public Svc(Port p<caret>ort) {} }")
    val texts = myFixture.availableIntentions.map { it.text }
    assertTrue("qualifier fix expected, got $texts", texts.any { it.contains("Qualifier") })
    assertTrue("primary fix expected, got $texts", texts.any { it.contains("Primary") })

    myFixture.launchAction(myFixture.availableIntentions.first { it.text.contains("Qualifier") })
    assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("@Qualifier(\"fastPort\")"))
    myFixture.checkHighlighting()
    assertNotNull(slow)
  }

  fun testPrimaryAndProfilesResolve() {
    myFixture.addClass("package app; @org.springframework.stereotype.Component @org.springframework.context.annotation.Primary public class FastPort implements Port {}")
    myFixture.addClass("package app; @org.springframework.stereotype.Component public class SlowPort implements Port {}")
    myFixture.addClass("package app; public interface Clock {}")
    myFixture.addClass("package app; @org.springframework.stereotype.Component @org.springframework.context.annotation.Profile(\"prod\") public class RealClock implements Clock {}")
    myFixture.addClass("package app; @org.springframework.stereotype.Component @org.springframework.context.annotation.Profile(\"dev\") public class FakeClock implements Clock {}")
    myFixture.configureByText("Svc.java", "package app; @org.springframework.stereotype.Component public class Svc { public Svc(Port port, Clock clock) {} }")
    myFixture.checkHighlighting()
  }

  fun testBeanMethodsCountAndGenericsNarrow() {
    myFixture.addClass("package app; public interface Repo<T> {}")
    myFixture.addClass("""
      package app;
      @org.springframework.context.annotation.Configuration
      public class Config {
        @org.springframework.context.annotation.Bean public Repo<String> strings() { return null; }
        @org.springframework.context.annotation.Bean public Repo<Integer> ints() { return null; }
        @org.springframework.context.annotation.Bean public Port one() { return null; }
        @org.springframework.context.annotation.Bean public Port two() { return null; }
      }
    """.trimIndent())
    myFixture.configureByText("Svc.java", """
      package app;
      @org.springframework.stereotype.Component
      public class Svc {
        public Svc(Repo<String> repo, Port <warning descr="${multiple("Port", "one, two")}">port</warning>) {}
      }
    """.trimIndent())
    myFixture.checkHighlighting()
  }
}
