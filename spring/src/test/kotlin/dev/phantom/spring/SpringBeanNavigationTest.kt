// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.spring.core.InjectionPoint
import dev.phantom.spring.core.PhantomSpringBundle
import dev.phantom.spring.core.SpringBeans
import dev.phantom.spring.core.ide.SpringImplicitUsageProvider
import org.jetbrains.uast.toUElement

class SpringBeanNavigationTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk


  override fun setUp() {
    super.setUp()
    val retention = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)"
    myFixture.addClass("package org.springframework.stereotype; $retention public @interface Component { String value() default \"\"; }")
    myFixture.addClass("package org.springframework.stereotype; $retention @Component public @interface Service { String value() default \"\"; }")
    myFixture.addClass("package org.springframework.context.annotation; $retention @org.springframework.stereotype.Component public @interface Configuration {}")
    myFixture.addClass("package org.springframework.context.annotation; $retention public @interface Bean { String[] name() default {}; String[] value() default {}; }")
    myFixture.addClass("package org.springframework.context.annotation; $retention public @interface Primary {}")
    myFixture.addClass("package org.springframework.beans.factory.annotation; $retention public @interface Qualifier { String value() default \"\"; }")
    myFixture.addClass("package org.springframework.context.event; $retention public @interface EventListener {}")
    // A user-defined stereotype, as in hexagonal projects
    myFixture.addClass("package app; $retention @org.springframework.stereotype.Service public @interface UseCase {}")
    myFixture.addClass("package app; public interface Port {}")
  }

  private fun pointAtCaret(): InjectionPoint {
    val parameter = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(myFixture.caretOffset), PsiParameter::class.java)!!
    return SpringBeans.injectionPointOf(parameter.toUElement()!!)!!
  }

  private fun names(beans: List<Any>): List<String?> = beans.map {
    when (it) {
      is PsiClass -> it.name
      is PsiMethod -> it.name + "()"
      else -> it.toString()
    }
  }

  fun testConstructorParameterResolvesToImplementationThroughMetaStereotype() {
    myFixture.addClass("package app; @org.springframework.stereotype.Component public class Adapter implements Port {}")
    myFixture.addClass("package app; public class NotABean implements Port {}")
    myFixture.configureByText("Svc.java", """
      package app;
      @UseCase
      public class Svc {
        public Svc(Port p<caret>ort) {}
      }
    """.trimIndent())

    assertEquals(listOf("Adapter"), names(SpringBeans.findCandidates(pointAtCaret())))
    val atCaret = myFixture.findGuttersAtCaret().map { it.tooltipText }
    assertEquals(listOf(PhantomSpringBundle.message("gutter.injection.tooltip")), atCaret)
    val all = myFixture.findAllGutters().map { it.tooltipText }
    assertTrue("bean gutter expected on the class, got $all", PhantomSpringBundle.message("gutter.bean.tooltip") in all)
  }

  fun testQualifierPrimaryAndBeanMethods() {
    myFixture.addClass("package app; @org.springframework.stereotype.Component(\"first\") public class A implements Port {}")
    myFixture.addClass("package app; @org.springframework.stereotype.Component @org.springframework.context.annotation.Primary public class B implements Port {}")
    myFixture.addClass("""
      package app;
      @org.springframework.context.annotation.Configuration
      public class Config { @org.springframework.context.annotation.Bean public Port extraPort() { return null; } }
    """.trimIndent())

    myFixture.configureByText("Svc.java", "package app; @UseCase public class Svc { public Svc(Port p<caret>ort) {} }")
    val all = names(SpringBeans.findCandidates(pointAtCaret()))
    assertEquals("B", all.first())
    assertSameElements(all, "A", "B", "extraPort()")

    myFixture.configureByText("Svc2.java", """
      package app;
      @UseCase public class Svc2 { public Svc2(@org.springframework.beans.factory.annotation.Qualifier("first") Port p<caret>ort) {} }
    """.trimIndent())
    assertEquals(listOf("A"), names(SpringBeans.findCandidates(pointAtCaret())))
  }

  fun testCollectionInjectionUnwrapsElementType() {
    myFixture.addClass("package app; @org.springframework.stereotype.Component public class Adapter implements Port {}")
    myFixture.configureByText("Svc.java", "package app; @UseCase public class Svc { public Svc(java.util.List<Port> p<caret>orts) {} }")
    assertEquals(listOf("Adapter"), names(SpringBeans.findCandidates(pointAtCaret())))
  }

  fun testBeanToInjectionPoints() {
    val adapter = myFixture.addClass("package app; @org.springframework.stereotype.Component public class Adapter implements Port {}")
    myFixture.addClass("package app; @UseCase public class Svc { public Svc(Port port) {} }")
    myFixture.addClass("package app; public class Plain { public Plain(Port port) {} }")
    val points = SpringBeans.findInjectionPoints(adapter)
    assertEquals(listOf("port"), points.map { (it.element as PsiParameter).name })
    assertEquals("Svc", (points.single().element as PsiParameter).let { PsiTreeUtil.getParentOfType(it, PsiClass::class.java)?.name })
  }

  fun testImplicitUsage() {
    val adapter = myFixture.addClass("""
      package app;
      @org.springframework.stereotype.Component
      public class Adapter implements Port {
        public Adapter() {}
        @org.springframework.context.event.EventListener public void on(Object event) {}
        public void notCalled() {}
      }
    """.trimIndent())
    val provider = SpringImplicitUsageProvider()
    assertTrue(provider.isImplicitUsage(adapter))
    assertTrue(provider.isImplicitUsage(adapter.constructors.single()))
    assertTrue(provider.isImplicitUsage(adapter.findMethodsByName("on", false).single()))
    assertFalse(provider.isImplicitUsage(adapter.findMethodsByName("notCalled", false).single()))
    assertFalse(provider.isImplicitUsage(myFixture.addClass("package app; public class Plain {}")))
  }

  fun testDefaultBeanName() {
    assertEquals("myService", SpringBeans.decapitalize("MyService"))
    assertEquals("URLMapper", SpringBeans.decapitalize("URLMapper"))
  }
}
