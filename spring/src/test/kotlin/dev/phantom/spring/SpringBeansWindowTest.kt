// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifierListOwner
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.spring.core.BeanKind
import dev.phantom.spring.core.SpringBeanCatalog
import dev.phantom.spring.core.ide.BeanNode
import dev.phantom.spring.core.ide.SpringBeansPanel
import javax.swing.tree.DefaultMutableTreeNode

class SpringBeansWindowTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  override fun setUp() {
    super.setUp()
    val retention = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)"
    myFixture.addClass("package org.springframework.stereotype; $retention public @interface Component { String value() default \"\"; }")
    myFixture.addClass("package org.springframework.stereotype; $retention @Component public @interface Service { String value() default \"\"; }")
    myFixture.addClass("package org.springframework.stereotype; $retention @Component public @interface Repository { String value() default \"\"; }")
    myFixture.addClass("package org.springframework.stereotype; $retention @Component public @interface Controller { String value() default \"\"; }")
    myFixture.addClass("package org.springframework.web.bind.annotation; $retention @org.springframework.stereotype.Controller public @interface RestController {}")
    myFixture.addClass("package org.springframework.context.annotation; $retention @org.springframework.stereotype.Component public @interface Configuration {}")
    myFixture.addClass("package org.springframework.context.annotation; $retention public @interface Bean { String[] name() default {}; String[] value() default {}; }")
    myFixture.addClass("package app; $retention @org.springframework.stereotype.Service public @interface UseCase {}")

    myFixture.addClass("package app; public interface MenuPort {}")
    myFixture.addClass("package app; @org.springframework.stereotype.Repository public class MenuAdapter implements MenuPort {}")
    myFixture.addClass("package app; public class Clock {}")
    myFixture.addClass("""
      package app;
      @org.springframework.context.annotation.Configuration
      public class AppConfig { @org.springframework.context.annotation.Bean public Clock clock() { return new Clock(); } }
    """.trimIndent())
    myFixture.addClass("package app; @UseCase public class FindMenu { public FindMenu(MenuPort port, Clock clock) {} }")
    myFixture.addClass("package app; @org.springframework.web.bind.annotation.RestController public class MenuController { public MenuController(FindMenu findMenu) {} }")
  }

  private fun name(element: PsiModifierListOwner): String = when (element) {
    is PsiMethod -> element.name + "()"
    is PsiClass -> element.name.orEmpty()
    else -> element.toString()
  }

  fun testCatalogGroupsBeansByStereotype() {
    val beans = SpringBeanCatalog.collect(project).values.single()
    assertEquals(listOf("menuController" to BeanKind.CONTROLLER, "findMenu" to BeanKind.SERVICE, "menuAdapter" to BeanKind.REPOSITORY,
                        "appConfig" to BeanKind.CONFIGURATION),
                 beans.map { it.name to it.kind })
    assertEquals(listOf("clock"), beans.last().beanMethods.map { it.name })
  }

  fun testDependenciesAndDependents() {
    val findMenu = myFixture.findClass("app.FindMenu")
    val dependencies = SpringBeanCatalog.dependencies(findMenu)
    assertEquals(listOf("port" to listOf("MenuAdapter"), "clock" to listOf("clock()")),
                 dependencies.map { it.point.name to it.candidates.map(::name) })
    assertEquals(listOf("MenuController"), SpringBeanCatalog.dependents(findMenu).map(::name))
    assertEquals(listOf("FindMenu"), SpringBeanCatalog.dependents(myFixture.findClass("app.AppConfig").findMethodsByName("clock", false)[0]).map(::name))
  }

  fun testWindowShowsTheTreeAndExpandsDependencies() {
    val panel = SpringBeansPanel(project)
    PlatformTestUtil.waitWithEventsDispatching("beans not loaded", { panel.loaded }, 20)
    val root = panel.tree.model.root as DefaultMutableTreeNode
    val groups = (root.firstChild as DefaultMutableTreeNode).children().toList().map { ((it as DefaultMutableTreeNode).userObject as BeanNode.Group).kind }
    assertEquals(listOf(BeanKind.CONTROLLER, BeanKind.SERVICE, BeanKind.REPOSITORY, BeanKind.CONFIGURATION), groups)

    val service = ((root.firstChild as DefaultMutableTreeNode).getChildAt(1) as DefaultMutableTreeNode).firstChild as DefaultMutableTreeNode
    val dependsOn = service.firstChild as DefaultMutableTreeNode
    panel.tree.expandPath(javax.swing.tree.TreePath(dependsOn.path))
    PlatformTestUtil.waitWithEventsDispatching("dependencies not loaded", { dependsOn.firstChild.let { (it as DefaultMutableTreeNode).userObject is BeanNode.Dependency } }, 20)
    assertEquals(listOf(listOf("MenuAdapter"), listOf("AppConfig.clock()")),
                 dependsOn.children().toList().map { ((it as DefaultMutableTreeNode).userObject as BeanNode.Dependency).candidates })
  }
}
