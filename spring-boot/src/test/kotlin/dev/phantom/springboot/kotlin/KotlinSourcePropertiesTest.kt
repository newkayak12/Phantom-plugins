// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springboot.kotlin

import dev.phantom.springboot.RealJdk
import dev.phantom.springboot.assertLayeredKeysMatchFlat
import com.intellij.psi.PsiNamedElement
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.springboot.core.BootConfigNavigation
import dev.phantom.springboot.core.SourceConfigProperties

/** Kotlin `@ConfigurationProperties` data classes, shaped like the ones in a typical Spring Boot Kotlin project. */
class KotlinSourcePropertiesTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  override fun setUp() {
    super.setUp()
    myFixture.addClass("package org.springframework.boot; public class SpringApplication {}")
    myFixture.addClass("""
      package org.springframework.boot.context.properties;
      @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
      public @interface ConfigurationProperties { String value() default ""; String prefix() default ""; }
    """.trimIndent())
    myFixture.addFileToProject("app/Props.kt", """
      package app
      import org.springframework.boot.context.properties.ConfigurationProperties

      enum class Mode { SINGLE, CLUSTER }

      @ConfigurationProperties(prefix = "http-interface")
      data class HttpInterfaceProperties(
          val retry: RetryProperties,
          val allowed: List<String>?,
          val mode: Mode,
      ) {
          data class RetryProperties(
              val maxAttempts: Int = 3,
              val backoffMillis: Long = 500,
          )
      }

      @ConfigurationProperties(value = "security.jwt.properties")
      data class JwtProperties(val expireTime: Long, val secret: String)
    """.trimIndent())
  }

  override fun tearDown() {
    try {
      assertLayeredKeysMatchFlat(module)
    }
    finally {
      super.tearDown()
    }
  }

  fun testKeysTypesAndDefaults() {
    val metadata = SourceConfigProperties.metadata(module)
    val byName = metadata.properties.associateBy { it.name }
    assertSameElements(byName.keys, "http-interface.retry.max-attempts", "http-interface.retry.backoff-millis", "http-interface.allowed",
                       "http-interface.mode", "security.jwt.properties.expire-time", "security.jwt.properties.secret")
    assertEquals("3", byName["http-interface.retry.max-attempts"]!!.defaultValue)
    assertEquals("app.Mode", byName["http-interface.mode"]!!.type)
    assertTrue(metadata.groups.containsKey("http-interface.retry"))
  }

  fun testNavigationToConstructorProperty() {
    val target = BootConfigNavigation.targets(module, "http-interface.retry.max-attempts").single() as PsiNamedElement
    assertEquals("maxAttempts", target.name)
    assertEquals("Props.kt", target.containingFile.name)
    assertEquals("JwtProperties", (BootConfigNavigation.targets(module, "security.jwt.properties").single() as PsiNamedElement).name)
  }
}
