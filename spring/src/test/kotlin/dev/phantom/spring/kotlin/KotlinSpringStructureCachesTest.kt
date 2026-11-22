// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.kotlin

import com.intellij.openapi.application.WriteAction
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.spring.RealJdkWithKotlin
import dev.phantom.spring.SpringStubs
import dev.phantom.spring.StructureCaches
import dev.phantom.spring.core.SpringStructureTracker
import dev.phantom.spring.core.ide.SpringBeanNames
import org.jetbrains.kotlin.psi.*

/**
 * Kotlin: block bodies and bodies of declarations with a written type are kept; expression bodies and initializers
 * whose type is inferred, `const val` initializers and bodies with classes count as declarations.
 */
class KotlinSpringStructureCachesTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdkWithKotlin

  private lateinit var caches: StructureCaches

  override fun setUp() {
    super.setUp()
    SpringStubs.add(myFixture)
    caches = StructureCaches(myFixture)
  }

  private fun add(path: String, text: String) = myFixture.addFileToProject(path, text.trimIndent()).virtualFile

  fun testKotlinEdits() {
    add("app/Repo.kt", "package app\ninterface Repo\nopen class Other")
    val impl = add("app/RepoImpl.kt", """
      package app
      @org.springframework.stereotype.Component
      class RepoImpl : Repo {
          fun work() { val a = 1 }
          fun typed(): Int = 1
          val typedProp: Int = 1
          val lazyProp: Int by lazy { 1 }
          init { println("a") }
      }
    """)
    val config = add("app/Config.kt", """
      package app
      import org.springframework.context.annotation.Bean
      const val NAME = "named"
      val untyped = 1
      @org.springframework.context.annotation.Configuration
      class Config {
          val task: Runnable = Runnable { val x = 1 }
          @Bean fun repoBean(): Repo { return RepoImpl() }
          @Bean fun inferred() = RepoImpl()
          @Bean(NAME) fun other(): Any = Any()
      }
    """)
    val user = add("app/UseIt.kt", """
      package app
      @org.springframework.stereotype.Service
      class UseIt(val repo: Repo, val other: Any) {
          fun m() { }
      }
    """)
    val plain = add("app/Plain.kt", "package app\nclass Plain")
    caches.assertParity()
    assertTrue(caches.candidateCount() >= 2)

    caches.assertKept(impl, "val a = 1", "val a = 2; val s = \"x\"")
    caches.assertKept(impl, "Int = 1\n", "Int = 2\n")
    caches.assertKept(impl, "typedProp: Int = 1", "typedProp: Int = 3")
    caches.assertKept(impl, "lazy { 1 }", "lazy { 4 }")
    caches.assertKept(impl, "println(\"a\")", "println(\"b\")")
    caches.assertKept(config, "val x = 1", "val x = 2")
    caches.assertKept(config, "return RepoImpl()", "val r = RepoImpl(); return r")
    caches.assertKept(plain, "class Plain", "// a comment\nclass Plain")
    // the inferred return type of a @Bean function changes which injection points it fills
    caches.assertRecomputed(config, "fun inferred() = RepoImpl()", "fun inferred() = Other()")
    caches.assertRecomputed(config, "val untyped = 1", "val untyped = \"s\"")
    caches.assertRecomputed(config, "\"named\"", "\"renamed\"")
    assertNotNull(SpringBeanNames.byName(project)["renamed"])
    caches.assertRecomputed(user, "fun m() { }", "fun m() { object : Repo { @org.springframework.context.annotation.Bean fun z(): Any = Any() } }")
    caches.assertRecomputed(user, "Any = Any()", "Any = 1")
    caches.assertRecomputed(plain, "class Plain", "@org.springframework.stereotype.Component class Plain")
    caches.assertRecomputed(config, "repoBean()", "repoBean2()")
    assertNotNull(SpringBeanNames.byName(project)["repoBean2"])
    caches.assertRecomputed(plain, "class Plain", "class Plain : Repo")

    val before = SpringStructureTracker.getInstance(project).modificationCount
    add("app/Added.kt", "package app\n@org.springframework.stereotype.Component class Added : Repo")
    assertTrue(SpringStructureTracker.getInstance(project).modificationCount > before)
    caches.assertParity()
    assertNotNull(SpringBeanNames.byName(project)["added"])

    WriteAction.run<Throwable> { impl.delete(this) }
    caches.assertParity()
    assertNull(SpringBeanNames.byName(project)["repoImpl"])
  }

  /** Refactorings and quick fixes write PSI directly, with other events than typing. */
  fun testKotlinPsiWrites() {
    add("app/Repo.kt", "package app\nfun interface Repo { fun go() }")
    val impl = add("app/Impl.kt", "package app\n@org.springframework.stereotype.Component class Impl : Repo { override fun go() {} }")
    val other = add("app/Other.kt", "package app\n@org.springframework.stereotype.Component class Other : Repo { override fun go() {} }")
    val config = add("app/Config.kt", """
      package app
      import org.springframework.context.annotation.Bean
      @org.springframework.context.annotation.Configuration
      class Config {
          @Bean fun repoBean(): Repo = Impl()
          @Bean fun second(): Repo = Impl()
      }
    """)
    val user = add("app/UseIt.kt", """
      package app
      @org.springframework.stereotype.Service
      class UseIt(val repo: Repo) {
          fun m() { val r = object : Repo { override fun go() {} } }
          fun n() { val b = 1; class Local : Repo { override fun go() {} } }
      }
    """)
    caches.assertParity()
    val factory = KtPsiFactory(project)
    fun PsiFile.cls(name: String) = PsiTreeUtil.findChildrenOfType(this, KtClass::class.java).single { it.name == name }
    fun PsiFile.fn(name: String) = PsiTreeUtil.findChildrenOfType(this, KtNamedFunction::class.java).single { it.name == name }

    caches.assertPsiKept(config, "an expression body with a written type replaced") { file ->
      file.fn("repoBean").bodyExpression!!.replace(factory.createExpression("Impl().also { }"))
    }
    caches.assertPsiRecomputed(config, "a @Bean function deleted") { it.fn("second").delete() }
    caches.assertPsiRecomputed(impl, "a supertype removed") { file ->
      file.cls("Impl").run { removeSuperTypeListEntry(superTypeListEntries.single()) }
    }
    caches.assertPsiRecomputed(user, "an object literal replaced with a lambda") { file ->
      PsiTreeUtil.findChildOfType(file, KtObjectLiteralExpression::class.java)!!.replace(factory.createExpression("Repo { }"))
    }
    caches.assertPsiRecomputed(other, "an annotation entry deleted") { it.cls("Other").annotationEntries.single().delete() }
    caches.assertPsiRecomputed(user, "a local class deleted") { it.cls("Local").delete() }
  }
}
