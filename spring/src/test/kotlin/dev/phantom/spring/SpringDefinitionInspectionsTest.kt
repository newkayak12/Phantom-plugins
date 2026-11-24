// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.spring.core.PhantomSpringBundle
import dev.phantom.spring.core.ide.*

/** Bean definition checks: stereotypes on abstract types, @Value literals, @Bean methods, scanned packages, resources, @TestBean. */
class SpringDefinitionInspectionsTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  private val runtime = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)"

  override fun setUp() {
    super.setUp()
    SpringStubs.add(myFixture)
    listOf(
      "package org.springframework.stereotype; $runtime @Component public @interface Controller { String value() default \"\"; }",
      "package org.springframework.stereotype; $runtime @Component public @interface Repository { String value() default \"\"; }",
      "package org.springframework.beans.factory.annotation; $runtime public @interface Lookup { String value() default \"\"; }",
      "package org.springframework.context.annotation; $runtime public @interface ComponentScan { String[] value() default {}; String[] basePackages() default {}; @interface Filter {} }",
      "package org.springframework.context.annotation; $runtime public @interface PropertySource { String[] value(); boolean ignoreResourceNotFound() default false; }",
      "package org.springframework.context.annotation; $runtime public @interface ImportResource { String[] value() default {}; String[] locations() default {}; }",
      "package org.springframework.context.annotation; $runtime public @interface Import { Class<?>[] value(); }",
      "package org.springframework.test.context; $runtime public @interface TestPropertySource { String[] value() default {}; String[] locations() default {}; }",
      "package org.springframework.test.context.bean.override.convention; $runtime public @interface TestBean { String methodName() default \"\"; String name() default \"\"; }",
      "package app; public class Foo {}",
    ).forEach { myFixture.addClass(it) }
  }

  private fun problems(vararg tools: LocalInspectionTool): List<String> {
    myFixture.enableInspections(*tools)
    return myFixture.doHighlighting().filter { it.inspectionToolId != null }.mapNotNull { it.description }.sorted()
  }

  private fun msg(key: String, vararg params: Any) = PhantomSpringBundle.message(key, *params)

  fun testCacheableAndCachePut() {
    myFixture.configureByText("Cached.java", """
      package app;
      import org.springframework.cache.annotation.*;
      class Cached {
        @Cacheable("a") @CachePut("a") String both() { return ""; }
        @Cacheable("a") String read() { return ""; }
        @CachePut("a") String write() { return ""; }
      }
    """.trimIndent())
    assertEquals(listOf(msg("inspection.cacheable.put.message")), problems(SpringCacheableAndCachePutInspection()))
  }

  fun testAbstractBeans() {
    myFixture.addClass("package app; public @interface Mapper {}")
    myFixture.configureByText("Beans.java", """
      package app;
      import org.springframework.stereotype.*;
      @Service abstract class AbstractService {}
      @Component interface Port {}
      @Component @Mapper interface UserMapper {}
      @Repository interface Plain {}
      @Service class RealService {}
      @Component abstract class WithLookup { @org.springframework.beans.factory.annotation.Lookup abstract Foo foo(); }
    """.trimIndent())
    assertEquals(listOf(
      msg("inspection.abstract.bean.abstract", "@Service"),
      msg("inspection.abstract.bean.interface", "@Component"),
    ).sorted(), problems(SpringAbstractBeanInspection()))
  }

  fun testValueLiteralTypes() {
    myFixture.configureByText("Settings.java", """
      package app;
      import org.springframework.beans.factory.annotation.Value;
      class Settings {
        @Value("ten") int a;
        @Value(" 0x1F ") int b;
        @Value("yes") boolean c;
        @Value("maybe") Boolean d;
        @Value("${'$'}{retries}") int e;
        @Value("300") byte f;
        @Value("abc") String g;
        @Value("1.5e3") double h;
        Settings(@Value("x1") long i) {}
      }
    """.trimIndent())
    assertEquals(listOf(
      msg("inspection.value.type.message", "maybe", "Boolean"),
      msg("inspection.value.type.message", "300", "byte"),
      msg("inspection.value.type.message", "ten", "int"),
      msg("inspection.value.type.message", "x1", "long"),
    ).sorted(), problems(SpringValueTypeInspection()))
  }

  fun testBeanMethods() {
    myFixture.configureByText("Config.java", """
      package app;
      import org.springframework.context.annotation.*;
      @Configuration class Config {
        @Bean void nothing() {}
        @Bean private Foo hidden() { return new Foo(); }
        @Bean final Foo sealed() { return new Foo(); }
        @Bean static Foo shared() { return new Foo(); }
        @Bean Foo fine() { return new Foo(); }
      }
      @Configuration(proxyBeanMethods = false) class Lite { @Bean private Foo ok() { return new Foo(); } }
      @Configuration final class Closed { @Bean Foo foo() { return new Foo(); } }
    """.trimIndent())
    assertEquals(listOf(
      msg("inspection.bean.method.void"),
      msg("inspection.bean.method.modifier", "private"),
      msg("inspection.bean.method.modifier", "final"),
      msg("inspection.bean.method.final.class"),
    ).sorted(), problems(SpringBeanMethodInspection()))
  }

  fun testScanPackages() {
    myFixture.configureByText("App.java", """
      package app;
      @org.springframework.context.annotation.ComponentScan({"app", "app.missing", "com.*.x", "java.util"})
      class App {}
    """.trimIndent())
    assertEquals(listOf(msg("inspection.scan.package.message", "app.missing")), problems(SpringScanPackageInspection()))
  }

  fun testAutowiredOutsideBeans() {
    myFixture.configureByText("Wiring.java", """
      package app;
      import org.springframework.beans.factory.annotation.Autowired;
      import org.springframework.context.annotation.*;
      class Helper { @Autowired Foo foo; }
      class Built { @Autowired Foo foo; }
      class Imported { @Autowired Foo foo; }
      class Manual { @Autowired Foo foo; }
      class Base { @Autowired Foo foo; }
      @org.springframework.stereotype.Component class Child extends Base {}
      @org.springframework.stereotype.Service class Svc { @Autowired Foo foo; Manual make() { return new Manual(); } }
      @Configuration @Import(Imported.class) class Config { @Bean Built built() { return new Built(); } }
    """.trimIndent())
    assertEquals(listOf(
      msg("inspection.autowired.outside.bean.message", "Helper"),
      msg("inspection.autowired.outside.bean.message", "Manual"),
    ).sorted(), problems(SpringAutowiredOutsideBeanInspection()))
  }

  /** A scan filter added to an existing class makes any class a possible component: the cached lookup sees it. */
  fun testScanFilterAddedLaterSilencesAutowiredOutsideBean() {
    val scan = myFixture.addFileToProject("app/Scan.java", "package app; class Scan {}")
    myFixture.configureByText("Helper.java", "package app; class Helper { @org.springframework.beans.factory.annotation.Autowired Foo foo; }")
    assertEquals(listOf(msg("inspection.autowired.outside.bean.message", "Helper")), problems(SpringAutowiredOutsideBeanInspection()))
    edit(scan, "class Scan", "@org.springframework.context.annotation.ComponentScan.Filter class Scan")
    assertEmpty(problems(SpringAutowiredOutsideBeanInspection()))
  }

  private fun edit(file: PsiFile, from: String, to: String) {
    val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
    WriteCommandAction.runWriteCommandAction(project) {
      val at = document.text.indexOf(from)
      document.replaceString(at, at + from.length, to)
    }
    PsiDocumentManager.getInstance(project).commitAllDocuments()
  }

  fun testResourceLocations() {
    myFixture.addFileToProject("app.properties", "a=1\n")
    myFixture.addFileToProject("app/local.properties", "a=1\n")
    myFixture.configureByText("Props.java", """
      package app;
      import org.springframework.context.annotation.*;
      @PropertySource({"classpath:app.properties", "classpath:missing.properties", "file:/etc/app.properties", "${'$'}{env}.properties"})
      class Props {}
      @PropertySource(value = "gone.properties", ignoreResourceNotFound = true) class Optional {}
      @org.springframework.test.context.TestPropertySource("local.properties") class LocalTest {}
      @org.springframework.test.context.TestPropertySource("/nope.properties") class NopeTest {}
    """.trimIndent())
    assertEquals(listOf(
      msg("inspection.resource.location.message", "/nope.properties"),
      msg("inspection.resource.location.message", "classpath:missing.properties"),
    ).sorted(), problems(SpringResourceLocationInspection()))
  }

  fun testTestBeanFactories() {
    myFixture.configureByText("OrderTest.java", """
      package app;
      import org.springframework.test.context.bean.override.convention.TestBean;
      class OrderTest {
        @TestBean Foo foo;
        @TestBean Foo bar;
        @TestBean(methodName = "make") Foo baz;
        @TestBean(methodName = "absent") Foo qux;
        static Foo foo() { return new Foo(); }
        static Foo make() { return new Foo(); }
      }
    """.trimIndent())
    assertEquals(listOf(
      msg("inspection.test.bean.factory.message", "absent"),
      msg("inspection.test.bean.factory.message", "bar"),
    ).sorted(), problems(SpringTestBeanFactoryInspection()))
  }

  fun testKotlinDefinitions() {
    myFixture.configureByText("Kotlin.kt", """
      package app
      import org.springframework.beans.factory.annotation.Value
      import org.springframework.context.annotation.*
      import org.springframework.stereotype.Service
      @Service abstract class AbstractKotlinService
      class Settings(@Value("many") val retries: Int, @Value("8") val threads: Int)
      @Configuration
      class KotlinConfig {
          @Bean fun nothing() {}
          @Bean fun foo(): Foo = Foo()
      }
    """.trimIndent())
    assertEquals(listOf(
      msg("inspection.abstract.bean.abstract", "@Service"),
      msg("inspection.bean.method.void"),
      msg("inspection.value.type.message", "many", "int"),
    ).sorted(), problems(SpringAbstractBeanInspection(), SpringValueTypeInspection(), SpringBeanMethodInspection()))
  }
}
