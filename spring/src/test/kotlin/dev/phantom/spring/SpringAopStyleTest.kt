// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.spring.core.PhantomSpringBundle
import dev.phantom.spring.core.ide.*

/** Advice signatures, pointcut methods, argNames, @DeclareParents and the auto-proxy switch. */
class SpringAopStyleTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  override fun setUp() {
    super.setUp()
    SpringStubs.add(myFixture)
    val runtime = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)"
    val p = "package org.aspectj.lang.annotation;"
    listOf(
      "$p $runtime public @interface Aspect { String value() default \"\"; }",
      "$p $runtime public @interface Pointcut { String value() default \"\"; String argNames() default \"\"; }",
      "$p $runtime public @interface Before { String value(); String argNames() default \"\"; }",
      "$p $runtime public @interface Around { String value(); String argNames() default \"\"; }",
      "$p $runtime public @interface DeclareParents { String value(); Class<?> defaultImpl() default DeclareParents.class; }",
      "package org.aspectj.lang; public interface JoinPoint { Object[] getArgs(); interface StaticPart {} }",
      "package org.aspectj.lang; public interface ProceedingJoinPoint extends JoinPoint { Object proceed() throws Throwable; }",
      "package app; public interface Auditable {}",
      "package app; public class AuditImpl implements Auditable {}",
      "package app; public abstract class AbstractAudit implements Auditable {}",
      "package app; public class Unrelated {}",
    ).forEach { myFixture.addClass(it) }
  }

  private fun problems(vararg tools: LocalInspectionTool): List<String> {
    myFixture.enableInspections(*tools)
    return myFixture.doHighlighting().filter { it.inspectionToolId != null }.mapNotNull { it.description }.sorted()
  }

  private fun msg(key: String, vararg params: Any) = PhantomSpringBundle.message(key, *params)

  fun testAdviceSignatures() {
    myFixture.configureByText("Timing.java", """
      package app;
      import org.aspectj.lang.*;
      import org.aspectj.lang.annotation.*;
      @Aspect class Timing {
        @Around("execution(* app..*(..))") Object ok(ProceedingJoinPoint pjp) throws Throwable { return pjp.proceed(); }
        @Around("execution(* app..*(..))") Object delegated(ProceedingJoinPoint pjp) throws Throwable { return run(pjp); }
        @Around("execution(* app..*(..))") Object noPjp(JoinPoint jp) { return null; }
        @Around("execution(* app..*(..))") Object swallow(ProceedingJoinPoint pjp) { return null; }
        @Around("execution(* app..*(..))") void lost(ProceedingJoinPoint pjp) throws Throwable { pjp.proceed(); }
        @Before("execution(* app..*(..))") void wrong(ProceedingJoinPoint pjp) {}
        @Before("execution(* app..*(..))") void fine(JoinPoint jp) {}
        Object run(ProceedingJoinPoint pjp) throws Throwable { return pjp.proceed(); }
      }
    """.trimIndent())
    assertEquals(listOf(
      msg("aop.advice.around.no.pjp"),
      msg("aop.advice.around.no.proceed"),
      msg("aop.advice.around.void"),
      msg("aop.advice.pjp.not.around"),
    ).sorted(), problems(AopAdviceSignatureInspection()))
  }

  fun testPointcutMethodsAndArgNames() {
    myFixture.configureByText("Points.java", """
      package app;
      import org.aspectj.lang.*;
      import org.aspectj.lang.annotation.*;
      @Aspect class Points {
        @Pointcut("within(app..*)") void good() {}
        @Pointcut("within(app..*)") boolean valued() { return true; }
        @Pointcut("within(app..*)") void busy() { System.out.println(); }
        @Pointcut("args(i) && if()") static boolean condition(int i) { return i > 0; }
        @Before(value = "good() && args(a, b)", argNames = "a,b") void two(JoinPoint jp, String a, int b) {}
        @Before(value = "good() && args(a, b)", argNames = "jp,a,b") void three(JoinPoint jp, String a, int b) {}
        @Before(value = "good() && args(a)", argNames = "a,b") void mismatch(String a) {}
      }
    """.trimIndent())
    assertEquals(listOf(
      msg("aop.arg.names.count", 2, 1),
      msg("aop.pointcut.method.body"),
      msg("aop.pointcut.method.not.void"),
    ).sorted(), problems(AopPointcutMethodInspection(), AopArgNamesInspection()))
  }

  fun testDeclareParents() {
    myFixture.configureByText("Intro.java", """
      package app;
      import org.aspectj.lang.annotation.*;
      @Aspect class Intro {
        @DeclareParents(value = "app.service.*+", defaultImpl = AuditImpl.class) public static Auditable ok;
        @DeclareParents(value = "app.service.*+", defaultImpl = AbstractAudit.class) public static Auditable abstractImpl;
        @DeclareParents(value = "app.service.*+", defaultImpl = Unrelated.class) public static Auditable unrelated;
        @DeclareParents(value = "app.service.*+", defaultImpl = AuditImpl.class) public static AuditImpl notInterface;
        @DeclareParents("app.service.*+") public static Auditable noImpl;
      }
    """.trimIndent())
    assertEquals(listOf(
      msg("aop.declare.parents.abstract.impl", "AbstractAudit"),
      msg("aop.declare.parents.impl.mismatch", "Unrelated", "Auditable"),
      msg("aop.declare.parents.not.interface", "AuditImpl"),
    ).sorted(), problems(AopDeclareParentsInspection()))
  }

  fun testAspectWithoutAutoProxy() {
    myFixture.configureByText("Logging.java", """
      package app;
      @org.aspectj.lang.annotation.Aspect @org.springframework.stereotype.Component class Logging {}
      @org.aspectj.lang.annotation.Aspect class Woven {}
    """.trimIndent())
    assertEquals(listOf(msg("inspection.aspectj.autoproxy.message")), problems(SpringAspectjAutoproxyInspection()))
  }

  fun testAspectWithAutoProxy() {
    myFixture.addClass("package org.springframework.context.annotation; public @interface EnableAspectJAutoProxy {}")
    myFixture.addClass("package app; @org.springframework.context.annotation.EnableAspectJAutoProxy public class AopConfig {}")
    myFixture.configureByText("Logging.java", """
      package app;
      @org.aspectj.lang.annotation.Aspect @org.springframework.stereotype.Component class Logging {}
    """.trimIndent())
    assertEquals(emptyList<String>(), problems(SpringAspectjAutoproxyInspection()))
  }

  fun testKotlinAdvice() {
    myFixture.configureByText("KTiming.kt", """
      package app
      import org.aspectj.lang.ProceedingJoinPoint
      import org.aspectj.lang.annotation.*
      @Aspect
      class KTiming {
          @Around("execution(* app..*(..))")
          fun ok(pjp: ProceedingJoinPoint): Any? = pjp.proceed()
          @Around("execution(* app..*(..))")
          fun swallow(pjp: ProceedingJoinPoint): Any? = null
          @Pointcut("within(app..*)")
          fun valued(): Boolean = true
      }
    """.trimIndent())
    assertEquals(listOf(
      msg("aop.advice.around.no.proceed"),
      msg("aop.pointcut.method.not.void"),
    ).sorted(), problems(AopAdviceSignatureInspection(), AopPointcutMethodInspection()))
  }
}
