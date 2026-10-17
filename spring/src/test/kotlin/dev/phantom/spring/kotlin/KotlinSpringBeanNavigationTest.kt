// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.kotlin

import dev.phantom.spring.RealJdk
import com.intellij.codeInsight.daemon.ImplicitUsageProvider
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.spring.core.PhantomSpringBundle
import dev.phantom.spring.core.SpringBeans
import dev.phantom.spring.core.ide.SpringImplicitUsageProvider
import org.jetbrains.uast.UParameter
import org.jetbrains.uast.getUParentForIdentifier
import org.jetbrains.uast.toUElement

/** The same bean navigation on Kotlin sources: primary constructors, meta stereotypes, light classes. */
class KotlinSpringBeanNavigationTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  override fun setUp() {
    super.setUp()
    val retention = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)"
    myFixture.addClass("package org.springframework.stereotype; $retention public @interface Component { String value() default \"\"; }")
    myFixture.addClass("package org.springframework.stereotype; $retention @Component public @interface Service { String value() default \"\"; }")
    myFixture.addClass("package org.springframework.context.annotation; $retention public @interface Bean { String[] name() default {}; String[] value() default {}; }")
    myFixture.addClass("package org.springframework.context.event; $retention public @interface EventListener {}")
  }

  fun testPrimaryConstructorParameterResolvesToKotlinAdapter() {
    myFixture.addFileToProject("app/UseCase.kt", """
      package app
      @org.springframework.stereotype.Service
      annotation class UseCase
    """.trimIndent())
    myFixture.addFileToProject("app/Port.kt", "package app\ninterface FindThings")
    myFixture.addFileToProject("app/Adapter.kt", """
      package app
      @org.springframework.stereotype.Component
      class FindThingsAdapter : FindThings {
        @org.springframework.context.event.EventListener
        fun on(event: Any) {}
      }
    """.trimIndent())
    myFixture.configureByText("Service.kt", """
      package app
      @UseCase
      class FindThingsService(
        private val find<caret>Things: FindThings,
      )
    """.trimIndent())

    val identifier = myFixture.file.findElementAt(myFixture.caretOffset)!!
    val parent = getUParentForIdentifier(identifier)
    assertNotNull("UAST parent for the constructor property", parent)
    val point = SpringBeans.injectionPointOf(parent!!)
    assertNotNull("injection point for ${parent.javaClass.simpleName}", point)
    val candidates = SpringBeans.findCandidates(point!!).map { (it as PsiClass).name }
    assertEquals(listOf("FindThingsAdapter"), candidates)

    val atCaret = myFixture.findGuttersAtCaret().map { it.tooltipText }
    assertEquals(listOf(PhantomSpringBundle.message("gutter.injection.tooltip")), atCaret)

    // Reverse direction and the implicit-usage provider work through Kotlin light classes.
    val adapter = myFixture.findClass("app.FindThingsAdapter")
    val points = SpringBeans.findInjectionPoints(adapter)
    assertEquals(1, points.size)
    assertTrue(points.single().navigationTarget.text.contains("findThings"))

    val provider: ImplicitUsageProvider = SpringImplicitUsageProvider()
    assertTrue(provider.isImplicitUsage(adapter))
    assertTrue(provider.isImplicitUsage(adapter.findMethodsByName("on", false).single() as PsiMethod))
    assertTrue(provider.isImplicitUsage(myFixture.findClass("app.FindThingsService")))
    assertTrue((point.element.toUElement() as? UParameter) != null)
  }
}
