// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring

import com.intellij.codeInsight.daemon.GutterMark
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiMethod
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.spring.core.PhantomSpringBundle
import dev.phantom.spring.core.ide.AopAdviceMatching
import dev.phantom.spring.core.ide.SpringApiVersionNotConfiguredInspection
import dev.phantom.spring.core.ide.SpringApiVersionSyntaxInspection

/** Wave 9b: @Transactional/@Async, component scan and advised-method gutters; Spring 7 API versioning checks. */
class SpringProxyMarkersTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  private val runtime = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)"

  override fun setUp() {
    super.setUp()
    SpringStubs.add(myFixture)
    val aop = "package org.aspectj.lang.annotation;"
    listOf(
      "$aop $runtime public @interface Aspect { String value() default \"\"; }",
      "$aop $runtime public @interface Pointcut { String value() default \"\"; String argNames() default \"\"; }",
      "$aop $runtime public @interface Before { String value(); String argNames() default \"\"; }",
      "$aop $runtime public @interface Around { String value(); String argNames() default \"\"; }",
      "package org.aspectj.lang; public interface JoinPoint { Object[] getArgs(); }",
      "package org.aspectj.lang; public interface ProceedingJoinPoint extends JoinPoint { Object proceed() throws Throwable; }",
      "package org.springframework.context.annotation; $runtime public @interface ComponentScan { String[] value() default {}; String[] basePackages() default {}; Class<?>[] basePackageClasses() default {}; }",
      "package org.springframework.boot.autoconfigure; $runtime @org.springframework.context.annotation.Configuration public @interface SpringBootApplication { String[] scanBasePackages() default {}; Class<?>[] scanBasePackageClasses() default {}; }",
      "package app; $runtime public @interface Locked { String value() default \"\"; }",
    ).forEach { myFixture.addClass(it) }
  }

  private fun msg(key: String, vararg params: Any) = PhantomSpringBundle.message(key, *params)

  private fun gutters(): List<GutterMark> = myFixture.findAllGutters()

  private fun tooltips(): List<String> = gutters().mapNotNull { runCatching { it.tooltipText }.getOrNull() }

  private fun targetsOf(tooltip: String): List<String> {
    val mark = gutters().firstOrNull { runCatching { it.tooltipText }.getOrNull() == tooltip } ?: error("no gutter '$tooltip' in ${tooltips()}")
    val info = (mark as LineMarkerInfo.LineMarkerGutterIconRenderer<*>).lineMarkerInfo as RelatedItemLineMarkerInfo<*>
    return info.createGotoRelatedItems().mapNotNull { it.element }.map { e ->
      when (e) {
        is PsiMethod -> "${e.containingClass?.name}.${e.name}"
        is org.jetbrains.kotlin.psi.KtNamedFunction -> "${com.intellij.psi.util.PsiTreeUtil.getParentOfType(e, org.jetbrains.kotlin.psi.KtClassOrObject::class.java)?.name}.${e.name}"
        is PsiClass -> e.name.orEmpty()
        is PsiDirectory -> e.name
        else -> e.text
      }
    }.sorted()
  }

  fun testTransactionalAndAsyncGutters() {
    myFixture.configureByText("Orders.java", """
      package app;
      import org.springframework.transaction.annotation.*;
      import org.springframework.scheduling.annotation.Async;
      @org.springframework.stereotype.Service @Transactional class Orders {
        @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true) public String find() { return ""; }
        @Async("mailPool") public void mail() {}
        public void plain() {}
      }
    """.trimIndent())
    myFixture.doHighlighting()
    val tooltips = tooltips()
    assertTrue(tooltips.toString(), "<html>${msg("proxy.gutter.transactional", msg("proxy.gutter.defaults"))}</html>" in tooltips)
    assertTrue(tooltips.toString(), "<html>${msg("proxy.gutter.transactional", "propagation = Propagation.REQUIRES_NEW, readOnly = true")}</html>" in tooltips)
    assertTrue(tooltips.toString(), "<html>${msg("proxy.gutter.async", "\"mailPool\"")}</html>" in tooltips)
    // the class, find and mail; plain inherits the class setting but declares nothing
    assertEquals(tooltips.toString(), 3, tooltips.count { it.startsWith("<html>@") })
  }

  fun testComponentScanGutter() {
    myFixture.addClass("package app.core; public class Marker {}")
    myFixture.addClass("package app.web; public class Controller {}")
    myFixture.configureByText("App.java", """
      package app;
      @org.springframework.boot.autoconfigure.SpringBootApplication class App {}
      @org.springframework.context.annotation.ComponentScan(basePackages = "app.core", basePackageClasses = app.web.Controller.class) class Scan {}
    """.trimIndent())
    myFixture.doHighlighting()
    assertEquals(listOf("app"), targetsOf(msg("scan.gutter.tooltip", "app")))
    assertEquals(listOf("core", "web"), targetsOf(msg("scan.gutter.tooltip", "app.core, app.web")))
  }

  fun testTypePatterns() {
    val pattern = AopAdviceMatching.typePattern("com.app..*")!!
    assertTrue(pattern.matches("com.app.Service"))
    assertTrue(pattern.matches("com.app.order.Service"))
    assertFalse(pattern.matches("com.apps.Service"))
    assertTrue(AopAdviceMatching.typePattern("com.app.*Service")!!.matches("com.app.OrderService"))
    assertFalse(AopAdviceMatching.typePattern("com.app.*Service")!!.matches("com.app.sub.OrderService"))
    assertNull(AopAdviceMatching.typePattern("com.app.Service+"))
    assertNull(AopAdviceMatching.typePattern("Service"))
  }

  fun testAdvisedMethods() {
    myFixture.addClass("""
      package app;
      import org.aspectj.lang.ProceedingJoinPoint;
      import org.aspectj.lang.annotation.*;
      @Aspect @org.springframework.stereotype.Component public class Guard {
        @Pointcut("execution(* app.svc..*.find*(..))") void finders() {}
        @Around("@annotation(app.Locked)") public Object lock(ProceedingJoinPoint pjp) throws Throwable { return pjp.proceed(); }
        @Around("@annotation(locked)") public Object bound(ProceedingJoinPoint pjp, Locked locked) throws Throwable { return pjp.proceed(); }
        @Before("finders() && !@annotation(app.Locked)") public void log() {}
        @Before("bean(orders) && execution(* *(..))") public void undecided() {}
        @Before("within(app.other..*)") public void elsewhere() {}
        @Before("execution(void app.svc.Orders.*())") public void voids() {}
      }
    """.trimIndent())
    myFixture.addClass("package app; public class NotABean { @Locked public void save() {} }")
    myFixture.configureByText("Orders.java", """
      package app.svc;
      import app.Locked;
      @org.springframework.stereotype.Service public class Orders {
        @Locked public void save() {}
        public java.util.List<String> findAll() { return null; }
        @Locked public String findLocked() { return ""; }
        public void delete(long id) {}
        private void helper() {}
      }
    """.trimIndent())
    myFixture.doHighlighting()
    val advised = tooltips().filter { it.startsWith(msg("aop.advised.gutter.tooltip", "").trimEnd()) }
    assertEquals(advised.toString(), 3, advised.size)
    assertEquals(listOf("Guard.bound", "Guard.lock", "Guard.voids"),
                 targetsOf(msg("aop.advised.gutter.tooltip", "@Before Guard.voids, @Around Guard.bound, @Around Guard.lock")))
    assertEquals(listOf("Guard.log"), targetsOf(msg("aop.advised.gutter.tooltip", "@Before Guard.log")))
    assertEquals(listOf("Guard.bound", "Guard.lock"), targetsOf(msg("aop.advised.gutter.tooltip", "@Around Guard.bound, @Around Guard.lock")))
  }

  /** A body edit keeps the advice list and its compiled patterns; an edit of a pointcut updates it. */
  fun testAdvicesFollowDeclarationsOnly() {
    myFixture.configureByText("Guard.java", """
      package app;
      import org.aspectj.lang.annotation.*;
      @Aspect @org.springframework.stereotype.Component public class Guard {
        @Before("execution(* app.svc..*.find*(..))") public void log() { System.out.println(); }
      }
    """.trimIndent())
    val before = AopAdviceMatching.advices(project)
    assertEquals(listOf("execution(* app.svc..*.find*(..))"), before.map { it.expression })
    typeAfter("System.out.println(", "1")
    assertSame(before, AopAdviceMatching.advices(project))
    typeAfter(".find", "All")
    assertEquals(listOf("execution(* app.svc..*.findAll*(..))"), AopAdviceMatching.advices(project).map { it.expression })
  }

  /** Types [text] at the end of the first [anchor] in the open file and commits. */
  private fun typeAfter(anchor: String, text: String) {
    myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf(anchor) + anchor.length)
    myFixture.type(text)
    PsiDocumentManager.getInstance(project).commitAllDocuments()
  }

  fun testAdvisedKotlinMethods() {
    myFixture.configureByText("Locks.kt", """
      package app
      import org.aspectj.lang.ProceedingJoinPoint
      import org.aspectj.lang.annotation.*
      @Aspect @org.springframework.stereotype.Component
      class KGuard {
          @Around("@annotation(lock)")
          fun around(pjp: ProceedingJoinPoint, lock: Locked): Any? = pjp.proceed()
      }
      @org.springframework.stereotype.Service
      class Reservations {
          @Locked fun reserve() {}
          fun cancel() {}
      }
    """.trimIndent())
    myFixture.doHighlighting()
    assertEquals(listOf("KGuard.around"), targetsOf(msg("aop.advised.gutter.tooltip", "@Around KGuard.around")))
    assertEquals(1, tooltips().count { it.startsWith("Advised by") })
  }

  private fun versioningStubs(configurer: Boolean) {
    val web = "package org.springframework.web.bind.annotation;"
    listOf(
      "$web $runtime public @interface RequestMapping { String[] value() default {}; String version() default \"\"; }",
      "$web $runtime @RequestMapping public @interface GetMapping { String[] value() default {}; String version() default \"\"; }",
      "$web $runtime @org.springframework.stereotype.Component public @interface RestController {}",
      "package org.springframework.web.accept; public interface ApiVersionParser<V> { V parseVersion(String version); }",
      "package org.springframework.web.accept; public interface ApiVersionStrategy {}",
    ).forEach { myFixture.addClass(it) }
    if (configurer) {
      myFixture.addClass("package org.springframework.web.servlet.config.annotation; public class ApiVersionConfigurer { public ApiVersionConfigurer useRequestHeader(String name) { return this; } }")
      myFixture.addClass("package org.springframework.web.servlet.config.annotation; public interface WebMvcConfigurer { default void configureApiVersioning(ApiVersionConfigurer configurer) {} }")
    }
  }

  private val controller = """
    package app;
    import org.springframework.web.bind.annotation.*;
    @RestController class Users {
      @GetMapping(value = "/users", version = "1.2") String v12() { return ""; }
      @GetMapping(value = "/users", version = "2+") String v2() { return ""; }
      @GetMapping(value = "/users", version = "v3") String v3() { return ""; }
      @GetMapping(value = "/users", version = "1.x") String bad() { return ""; }
      @GetMapping("/plain") String plain() { return ""; }
    }
  """.trimIndent()

  private fun problems(): List<String> {
    myFixture.enableInspections(SpringApiVersionNotConfiguredInspection(), SpringApiVersionSyntaxInspection())
    return myFixture.doHighlighting().filter { it.inspectionToolId != null }.mapNotNull { it.description }.sorted()
  }

  fun testApiVersioningWithoutConfiguration() {
    versioningStubs(configurer = true)
    myFixture.configureByText("Users.java", controller)
    val unconfigured = msg("inspection.api.version.unconfigured.message")
    assertEquals(listOf(msg("inspection.api.version.syntax.message", "1.x"), unconfigured, unconfigured, unconfigured, unconfigured), problems())
  }

  fun testApiVersioningConfigured() {
    versioningStubs(configurer = true)
    myFixture.addClass("""
      package app;
      import org.springframework.web.servlet.config.annotation.*;
      @org.springframework.context.annotation.Configuration class Web implements WebMvcConfigurer {
        @Override public void configureApiVersioning(ApiVersionConfigurer configurer) { configurer.useRequestHeader("X-API-Version"); }
      }
    """.trimIndent())
    myFixture.configureByText("Users.java", controller)
    assertEquals(listOf(msg("inspection.api.version.syntax.message", "1.x")), problems())
  }

  fun testApiVersioningConfiguredByProperties() {
    versioningStubs(configurer = true)
    myFixture.addFileToProject("application.properties", "spring.mvc.apiversion.use.header=X-API-Version\n")
    myFixture.configureByText("Users.java", controller)
    assertEquals(listOf(msg("inspection.api.version.syntax.message", "1.x")), problems())
  }

  fun testApiVersioningNotOnClasspath() {
    versioningStubs(configurer = false)
    myFixture.configureByText("Users.java", controller)
    assertEquals(emptyList<String>(), problems())
  }
}
