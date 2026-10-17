// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.kotlin

import dev.phantom.spring.RealJdk
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.spring.core.PhantomSpringBundle
import dev.phantom.spring.core.ide.SpringAutowiringInspection

/** Kotlin constructor properties: nullable types and default values are optional injection. */
class KotlinSpringAutowiringInspectionTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  override fun setUp() {
    super.setUp()
    val retention = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)"
    myFixture.addClass("package org.springframework.stereotype; $retention public @interface Component { String value() default \"\"; }")
    myFixture.addClass("package org.springframework.beans.factory.annotation; $retention public @interface Qualifier { String value() default \"\"; }")
    myFixture.enableInspections(SpringAutowiringInspection())
  }

  fun testConfigurationPropertiesConstructorIsBinding() {
    myFixture.addClass("package org.springframework.boot.context.properties; public @interface ConfigurationProperties { String value() default \"\"; }")
    myFixture.configureByText("Props.kt", """
      package app
      @org.springframework.boot.context.properties.ConfigurationProperties("http-interface")
      data class HttpInterfaceProperties(val retry: Retry) {
        data class Retry(val maxAttempts: Int = 0)
      }
    """.trimIndent())
    myFixture.checkHighlighting()
  }

  fun testKotlinConstructor() {
    myFixture.addFileToProject("app/Port.kt", "package app\ninterface Port\nclass PlainAdapter : Port\ninterface Clock\n@org.springframework.stereotype.Component class A : Clock\n@org.springframework.stereotype.Component class B : Clock")
    val noBean = PhantomSpringBundle.message("inspection.autowiring.no.bean", "Port")
    val multiple = PhantomSpringBundle.message("inspection.autowiring.multiple", "Clock", "a, b")
    myFixture.configureByText("Svc.kt", """
      package app
      @org.springframework.stereotype.Component
      class Svc(
        private val <warning descr="$noBean">port</warning>: Port,
        private val nullable: Port?,
        private val withDefault: Port = PlainAdapter(),
        private val <warning descr="$multiple">clock</warning>: Clock,
        private val a: Clock,
        @org.springframework.beans.factory.annotation.Qualifier(value = "b") private val chosen: Clock,
      )
    """.trimIndent())
    myFixture.checkHighlighting()
  }
}
