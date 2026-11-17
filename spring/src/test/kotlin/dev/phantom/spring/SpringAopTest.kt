// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.spring.core.ide.AopPointcutInspection

/** Pointcut expressions, named pointcut references and returning/throwing bindings, and the advice gutter icon. */
class SpringAopTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  override fun setUp() {
    super.setUp()
    val runtime = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)"
    val p = "package org.aspectj.lang.annotation;"
    listOf(
      "$p $runtime public @interface Aspect { String value() default \"\"; }",
      "$p $runtime public @interface Pointcut { String value() default \"\"; String argNames() default \"\"; }",
      "$p $runtime public @interface Before { String value(); String argNames() default \"\"; }",
      "$p $runtime public @interface After { String value(); String argNames() default \"\"; }",
      "$p $runtime public @interface Around { String value(); String argNames() default \"\"; }",
      "$p $runtime public @interface AfterReturning { String value() default \"\"; String pointcut() default \"\"; String returning() default \"\"; }",
      "$p $runtime public @interface AfterThrowing { String value() default \"\"; String pointcut() default \"\"; String throwing() default \"\"; }",
    ).forEach { myFixture.addClass(it) }
    myFixture.addClass("""
      package app;
      public class Pointcuts {
        @org.aspectj.lang.annotation.Pointcut("within(app.service..*)") public void services() {}
      }
    """.trimIndent())
    myFixture.enableInspections(AopPointcutInspection::class.java)
  }

  private fun errors(): List<String> =
    myFixture.doHighlighting(HighlightSeverity.ERROR).mapNotNull(HighlightInfo::getDescription).filterNot { it.startsWith("[") }.sorted()

  fun testJavaAspect() {
    myFixture.configureByText("Logging.java", """
      package app;
      import org.aspectj.lang.annotation.*;
      @Aspect class Logging {
        @Pointcut("execution(* app..*Service.*(..))") void anyService() {}
        @Before("anyService() && @annotation(org.springframework.transaction.annotation.Transactional)") void ok1() {}
        @Around("(anyService() || app.Pointcuts.services()) && !within(app.web..*)") Object ok2() { return null; }
        @AfterReturning(pointcut = "anyService()", returning = "result") void ok3(Object result) {}
        @AfterThrowing(pointcut = "anyService()", throwing = "ex") void badThrowing(Throwable error) {}
        @Before("execution(* app..*(..)") void unbalanced() {}
        @Before("anyServce()") void typo() {}
        @Before("execution(* *(..)) within(app..*)") void missingOperator() {}
        @After("Pointcuts.services()") void samePackage() {}
      }
    """.trimIndent())
    assertEquals(listOf(
      "''ex'' is not a parameter of this advice method".replace("''", "'"),
      "Cannot resolve pointcut 'anyServce'",
      "Unbalanced parentheses in the pointcut expression",
      "Unexpected 'within' in the pointcut expression",
    ), errors())
  }

  fun testKotlinAspectAndGutter() {
    myFixture.configureByText("Timing.kt", """
      import org.aspectj.lang.annotation.*
      @Aspect
      class Timing {
          @Pointcut("bean(*Service)")
          fun services() {}
          @Around("services() and not missing()")
          fun time(): Any? = null
      }
    """.trimIndent())
    assertEquals(listOf("Cannot resolve pointcut 'missing'"), errors())
    val tooltips = myFixture.findAllGutters().mapNotNull { runCatching { it.tooltipText }.getOrNull() }.filter { it.startsWith("@") }
    assertEquals(listOf("@Around advice: services() and not missing()"), tooltips)
  }
}
