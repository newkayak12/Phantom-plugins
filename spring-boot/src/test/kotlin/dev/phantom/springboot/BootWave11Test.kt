// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springboot

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.springboot.core.BootBootstrapConfigInspection
import dev.phantom.springboot.core.BootCloudStreamBindingInspection
import dev.phantom.springboot.core.PhantomSpringBootBundle

/** Bootstrap files nothing reads and Cloud Stream bindings of missing functions. */
class BootWave11Test : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  override fun setUp() {
    super.setUp()
    myFixture.addClass("package org.springframework.boot; public class SpringApplication {}")
    myFixture.enableInspections(BootBootstrapConfigInspection::class.java, BootCloudStreamBindingInspection::class.java)
  }

  override fun tearDown() {
    try {
      assertLayeredKeysMatchFlat(module)
    }
    finally {
      super.tearDown()
    }
  }

  private fun problems(): List<String> = myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING)
    .filter { it.inspectionToolId?.startsWith("Phantom") == true }
    .map { "${myFixture.file.text.substring(it.startOffset, it.endOffset)}: ${it.description}" }.sorted()

  private fun msg(key: String, vararg params: Any) = PhantomSpringBootBundle.message(key, *params)

  fun testBootstrapWithoutCloud() {
    myFixture.configureByText("bootstrap.yml", "spring:\n  application:\n    name: app\n")
    assertEquals(listOf("spring:: " + msg("inspection.bootstrap.no.cloud")), problems())
  }

  fun testBootstrapWithModernCloudNeedsTheStarter() {
    myFixture.addClass("package org.springframework.cloud.bootstrap; public class BootstrapConfiguration {}")
    myFixture.addClass("package org.springframework.cloud.util; public class PropertyUtils {}")
    myFixture.configureByText("bootstrap.properties", "spring.application.name=app\n")
    assertEquals(listOf("spring.application.name=app: " + msg("inspection.bootstrap.no.starter")), problems())
    myFixture.addClass("package org.springframework.cloud.bootstrap.marker; public class Marker {}")
    assertEmpty(problems())
  }

  fun testBootstrapWithOldCloud() {
    myFixture.addClass("package org.springframework.cloud.bootstrap; public class BootstrapConfiguration {}")
    myFixture.configureByText("bootstrap.yml", "spring:\n  application:\n    name: app\n")
    assertEmpty(problems())
  }

  fun testStreamBindings() {
    myFixture.addClass("package org.springframework.cloud.stream.function; public class StreamBridge {}")
    myFixture.addClass("package org.springframework.context.annotation; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface Bean { String[] value() default {}; String[] name() default {}; }")
    myFixture.addClass("""
      package app;
      import org.springframework.context.annotation.Bean;
      import java.util.function.*;
      class Functions {
        @Bean Consumer<String> audit() { return s -> {}; }
        @Bean Function<String, String> upper() { return String::toUpperCase; }
        @Bean("rev") Function<String, String> reverse() { return s -> s; }
      }
    """.trimIndent())
    myFixture.configureByText("application.yml", """
      spring:
        cloud:
          function:
            definition: audit;upper|rev;missing
          stream:
            bindings:
              audit-in-0:
                destination: audit
              upperrev-in-0:
                destination: x
              unknown-in-0:
                destination: y
              anything-out-0:
                destination: z
    """.trimIndent())
    assertEquals(listOf("missing: " + msg("inspection.stream.unknown.definition", "missing"),
                        "unknown-in-0: " + msg("inspection.stream.unknown.function", "unknown", "unknown-in-0")), problems())
  }
}
