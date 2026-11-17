// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springboot.kotlin

import com.intellij.psi.PsiPolyVariantReference
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.springboot.RealJdk
import dev.phantom.springboot.core.ConfigText
import dev.phantom.springboot.core.ValuePlaceholders

/** `@Value("${...}")` in Java and Kotlin against the project's own application.yml / .properties. */
class ValuePlaceholderTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  override fun setUp() {
    super.setUp()
    myFixture.addClass("package org.springframework.boot; public class SpringApplication {}")
    myFixture.addClass("""
      package org.springframework.beans.factory.annotation;
      @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
      public @interface Value { String value(); }
    """.trimIndent())
    myFixture.addFileToProject("application.yml", """
      spring:
        application:
          name: reservation # the app
      jwt:
        secret-key: "s3cr3t"
        expiry: 3600
        description: |
          not: a key
      ---
      spring.config.activate.on-profile: temporary
      jwt:
        expiry: 60
    """.trimIndent())
    myFixture.addFileToProject("application-local.properties", "redis.host=localhost\nredis.port: 6379\n")
  }

  fun testFlattensYamlAndProperties() {
    val yaml = ConfigText.yaml("a:\n  b: 1\n  c:\n    d: 'x'\n  e: |\n    f: no\nlist:\n  - g: no\n")
    assertEquals(listOf("a.b" to "1", "a.c.d" to "x", "a.e" to ""), yaml.map { it.key to it.value })
    val props = ConfigText.properties("# c\nx.y=1\nlong=a \\\n  b\nz : 2")
    assertEquals(listOf("x.y", "long", "z"), props.map { it.key })
    assertEquals("# c\nx.".length, props[0].offset)
  }

  fun testKeyRanges() {
    assertEquals(listOf("jwt.secret"), ValuePlaceholders.keyRanges("\"\${jwt.secret}\"").map { it.substring("\"\${jwt.secret}\"") })
    assertEquals(listOf("a", "b"), ValuePlaceholders.keyRanges("\"\${a:\${b}}\"").map { it.substring("\"\${a:\${b}}\"") })
    assertEquals(listOf("jw"), ValuePlaceholders.keyRanges("\"\${jw\"").map { it.substring("\"\${jw\"") })
  }

  fun testJavaCompletionAndNavigation() {
    myFixture.configureByText("Jwt.java", """
      class Jwt { @org.springframework.beans.factory.annotation.Value("${'$'}{jwt.<caret>}") String key; }
    """.trimIndent())
    myFixture.completeBasic()
    assertContainsElements(myFixture.lookupElementStrings!!, "jwt.secret-key", "jwt.expiry")
    assertDoesntContain(myFixture.lookupElementStrings!!, "jwt.description.not")

    myFixture.configureByText("Redis.java", """
      class Redis { @org.springframework.beans.factory.annotation.Value("${'$'}{redis.po<caret>rt:6379}") int port; }
    """.trimIndent())
    val target = myFixture.elementAtCaret
    assertEquals("application-local.properties", target.containingFile.name)
    assertTrue(target.text, target.text.startsWith("redis.port"))
  }

  fun testKotlinPlaceholderResolvesToEveryProfile() {
    myFixture.configureByText("Jwt.kt", """
      import org.springframework.beans.factory.annotation.Value
      class Jwt(@Value("\${'$'}{jwt.exp<caret>iry}") val expiry: Long, @Value("\${'$'}{jwt.secretKey}") val key: String)
    """.trimIndent())
    val reference = myFixture.file.findReferenceAt(myFixture.caretOffset) as PsiPolyVariantReference
    val targets = reference.multiResolve(false).mapNotNull { it.element }
    assertEquals(2, targets.size)
    assertTrue(targets.all { it.containingFile.name == "application.yml" })
    // relaxed binding: secretKey finds secret-key
    val text = myFixture.file.text
    val secret = myFixture.file.findReferenceAt(text.indexOf("secretKey") + 1)!!
    assertNotNull(secret.resolve())
  }
}
