// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.JavaCodeInsightTestFixture
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.spring.core.PhantomSpringBundle
import dev.phantom.spring.core.SpringBeanCatalog
import dev.phantom.spring.core.SpringBeans
import dev.phantom.spring.core.ide.SpringBeanNames
import dev.phantom.spring.core.ide.SpringDuplicateBeanNameInspection
import junit.framework.TestCase

/** Bean names looked up from the name alone give what the full catalog gives, or defer to it. */
class SpringBeanNamesLookupTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  override fun setUp() {
    super.setUp()
    SpringStubs.add(myFixture)
    addDataStubs(myFixture)
  }

  fun testLiteralNamesMatchCatalog() {
    myFixture.addClass("package app; @org.springframework.stereotype.Component(\"clock\") public class SystemClock {}")
    myFixture.addClass("package app; @org.springframework.stereotype.Component @org.springframework.context.annotation.Profile(\"test\") public class FakeMailer {}")
    myFixture.addClass("""
      package app;
      import org.springframework.context.annotation.*;
      @Configuration
      public class Config {
        @Bean public Object clock() { return null; }
        @Bean public Object fakeMailer() { return null; }
        @Bean(name = "named") public Object x() { return null; }
        @Bean({"arrayName", "alias"}) public Object y() { return null; }
        @Bean public Object shared() { return null; }
        @Bean public Object shared(String overload) { return null; }
        public Object notABean() { return null; }
        @Configuration public static class Nested {
          @Bean public Object nestedBean() { return null; }
          @Bean("named") public Object z() { return null; }
        }
      }
    """.trimIndent())
    myFixture.addClass("package app; @org.springframework.stereotype.Service(\"shared\") public class SharedService {}")
    myFixture.addClass("package app; @org.springframework.stereotype.Component public class Shared {}")
    myFixture.addClass("package app; @org.springframework.stereotype.Component public @interface UseCase { String value() default \"\"; }")
    myFixture.addClass("package app; @UseCase(\"useCaseBean\") public class PlaceOrder {}")
    myFixture.addClass("package app; @org.springframework.stereotype.Service(value = \"useCaseBean\") public class OtherUseCase {}")
    myFixture.addClass("package app; public interface OrderRepository extends org.springframework.data.repository.Repository<Object, Long> {}")
    myFixture.addClass("package app.other; @org.springframework.stereotype.Component public class OrderRepository {}")
    myFixture.addClass("package app; @org.springframework.data.repository.NoRepositoryBean public interface BaseRepository extends org.springframework.data.repository.Repository<Object, Long> {}")
    myFixture.addClass("package app; @org.springframework.boot.context.properties.ConfigurationProperties(\"app\") public class AppProperties {}")
    myFixture.addClass("package app; @org.springframework.stereotype.Component public class appProperties {}")
    myFixture.addClass("package app; @org.springframework.stereotype.Component(\"app.dotted-bean\") public class Dotted1 {}")
    myFixture.addClass("package app; @org.springframework.stereotype.Service(\"app.dotted-bean\") public class Dotted2 {}")
    myFixture.addClass("package app; @org.springframework.stereotype.Service public class URLService {}")
    myFixture.addClass("package app; @org.springframework.stereotype.Service public abstract class AbstractService {}")
    myFixture.addClass("package app; public class Plain { public Object clock() { return null; } }")
    myFixture.addClass("package app; public class Mentions { String s = \"shared clock named\"; }")
    assertNamesMatchCatalog(project, expectLookup = true)
    // kinds and owners decide the order: service, component, then the configuration's method
    val shared = SpringBeanNames.named(project, "shared").map(::describe)
    assertEquals(SpringBeanNames.byName(project)["shared"].orEmpty().map(::describe), shared)
    assertEquals(listOf("app.SharedService", "app.Shared", "Config.shared", "Config.shared"), shared)
  }

  fun testConstantNameFallsBackToCatalog() {
    myFixture.addClass("package app; public class Names { public static final String N = \"constBean\"; }")
    myFixture.addClass("package app; @org.springframework.stereotype.Service(Names.N) public class C1 {}")
    myFixture.addClass("package app; @org.springframework.stereotype.Component(\"constBean\") public class C2 {}")
    assertNamesMatchCatalog(project, expectLookup = false)
    myFixture.enableInspections(SpringDuplicateBeanNameInspection())
    myFixture.configureFromExistingVirtualFile(myFixture.findClass("app.C2").containingFile.virtualFile)
    val messages = myFixture.doHighlighting().mapNotNull { it.description }
    assertTrue(messages.toString(), PhantomSpringBundle.message("inspection.duplicate.bean.message", "constBean", "app.C1") in messages)
  }

  fun testConcatenatedBeanNameFallsBackToCatalog() {
    myFixture.addClass("""
      package app;
      @org.springframework.context.annotation.Configuration
      public class Config { @org.springframework.context.annotation.Bean(name = "a" + "b") public Object x() { return null; } }
    """.trimIndent())
    myFixture.addClass("package app; @org.springframework.stereotype.Component public class Ab {}")
    assertNamesMatchCatalog(project, expectLookup = false)
  }

  fun testStereotypeDefaultFallsBackToCatalog() {
    myFixture.addClass("package app; @org.springframework.stereotype.Component public @interface Shared { String value() default \"sharedName\"; }")
    myFixture.addClass("package app; @Shared public class S1 {}")
    myFixture.addClass("package app; @org.springframework.stereotype.Component(\"sharedName\") public class S2 {}")
    assertNamesMatchCatalog(project, expectLookup = false)
  }

  /** The "some name is not a literal" flag follows created files, unsaved edits and changes on disk. */
  fun testFallbackFollowsEdits() {
    myFixture.addClass("package app; public class Names { public static final String N = \"constBean\"; }")
    myFixture.addClass("package app; @org.springframework.stereotype.Component(\"constBean\") public class C2 {}")
    assertNotNull(SpringBeanCatalog.named(project, "constBean"))
    val file = myFixture.addFileToProject("app/C1.java", "package app; @org.springframework.stereotype.Service(Names.N) public class C1 {}")
    assertNull(SpringBeanCatalog.named(project, "constBean"))
    myFixture.configureFromExistingVirtualFile(file.virtualFile)
    val document = myFixture.editor.document
    com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
      document.replaceString(document.text.indexOf("Names.N"), document.text.indexOf("Names.N") + "Names.N".length, "\"constBean\"")
    }
    com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
    assertEquals(setOf("app.C1", "app.C2"), SpringBeanCatalog.named(project, "constBean")!!.map(::describe).toSet())
    com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments()
    com.intellij.openapi.application.WriteAction.run<Throwable> {
      com.intellij.openapi.vfs.VfsUtil.saveText(file.virtualFile, "package app; @org.springframework.stereotype.Service(Names.N + \"\") public class C1 {}")
    }
    com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
    assertNull(SpringBeanCatalog.named(project, "constBean"))
    com.intellij.openapi.application.WriteAction.run<Throwable> { file.virtualFile.delete(this) }
    assertEquals(setOf("app.C2"), SpringBeanCatalog.named(project, "constBean")!!.map(::describe).toSet())
  }

  companion object {
    fun addDataStubs(fixture: JavaCodeInsightTestFixture) {
      fixture.addClass("package org.springframework.data.repository; public interface Repository<T, ID> {}")
      fixture.addClass("package org.springframework.data.repository; public @interface NoRepositoryBean {}")
      fixture.addClass("package org.springframework.boot.context.properties; public @interface ConfigurationProperties { String value() default \"\"; String prefix() default \"\"; }")
    }

    /**
     * For the name of every class and method in the project's Java and Kotlin sources and every catalog name: the
     * lookup by name gives the catalog's beans (or defers to the catalog when [expectLookup] is false), and so the
     * same duplicates for each of them.
     */
    fun assertNamesMatchCatalog(project: Project, expectLookup: Boolean) {
      val catalog = SpringBeanNames.byName(project)
      TestCase.assertTrue("catalog is empty", catalog.isNotEmpty())
      val scope = GlobalSearchScope.projectScope(project)
      val classes = listOf("java", "kt").flatMap { FilenameIndex.getAllFilesByExt(project, it, scope) }
        .mapNotNull { PsiManager.getInstance(project).findFile(it) as? PsiClassOwner }.flatMap { it.classes.toList() }.flatMap(::withNested)
      val names = (classes.flatMap { c -> listOfNotNull(SpringBeans.beanName(c), c.name) + c.methods.flatMap { listOfNotNull(SpringBeans.beanName(it), it.name) } } +
                   catalog.keys).toSet()
      for (name in names) {
        val expected = catalog[name].orEmpty()
        val lookup = SpringBeanCatalog.named(project, name)
        if (!expectLookup) {
          TestCase.assertNull(name, lookup)
          continue
        }
        TestCase.assertNotNull(name, lookup)
        TestCase.assertEquals(name, expected.map(::describe).sorted(), lookup!!.map(::describe).sorted())
        for (bean in expected) {
          TestCase.assertEquals("$name ${describe(bean)}", expected.filter { SpringBeanNames.clash(bean, it) }.toSet(),
                                lookup.filter { SpringBeanNames.clash(bean, it) }.toSet())
        }
      }
    }

    private fun withNested(psiClass: PsiClass): List<PsiClass> = listOf(psiClass) + psiClass.innerClasses.flatMap(::withNested)

    fun describe(bean: PsiModifierListOwner): String = when (bean) {
      is PsiClass -> bean.qualifiedName.orEmpty()
      is com.intellij.psi.PsiMethod -> "${bean.containingClass?.name}.${bean.name}"
      else -> bean.toString()
    }
  }
}
