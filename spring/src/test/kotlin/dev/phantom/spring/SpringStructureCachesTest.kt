// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring

import com.intellij.openapi.application.WriteAction
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.spring.core.SpringStructureTracker
import dev.phantom.spring.core.ide.SpringBeanNames

/**
 * Bean names and autowiring candidates are kept across edits inside method bodies and non-constant field
 * initializers, and recomputed, equal to a full search, after edits of declarations.
 */
class SpringStructureCachesTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  private lateinit var caches: StructureCaches

  override fun setUp() {
    super.setUp()
    SpringStubs.add(myFixture)
    caches = StructureCaches(myFixture)
  }

  private fun add(path: String, text: String) = myFixture.addFileToProject(path, text.trimIndent()).virtualFile

  fun testJavaEdits() {
    add("app/Repo.java", "package app; public interface Repo {}")
    val impl = add("app/RepoImpl.java", """
      package app;
      @org.springframework.stereotype.Component
      public class RepoImpl implements Repo {
        void work() { int a = 1; }
      }
    """)
    val config = add("app/Config.java", """
      package app;
      import org.springframework.context.annotation.Bean;
      @org.springframework.context.annotation.Configuration
      public class Config {
        static final String NAME = "named";
        private int counter = 0;
        private Runnable task = () -> { int x = 1; };
        @Bean Repo repoBean() { return new RepoImpl(); }
        @Bean(NAME) Object other() { return null; }
      }
    """)
    val user = add("app/UseIt.java", """
      package app;
      @org.springframework.stereotype.Service
      public class UseIt {
        UseIt(Repo repo, Object other) {}
        void m() { }
      }
    """)
    val plain = add("app/Plain.java", "package app; public class Plain { }")
    caches.assertParity()
    assertTrue(caches.candidateCount() >= 2)

    caches.assertKept(impl, "int a = 1;", "int a = 2; String s = \"x\";")
    caches.assertKept(config, "counter = 0", "counter = 5")
    caches.assertKept(config, "int x = 1;", "int x = 2;")
    caches.assertKept(config, "return new RepoImpl();", "Repo r = new RepoImpl(); return r;")
    caches.assertKept(plain, "{ }", "{ // a comment\n }")
    // a class declared in a body is found by class and annotation searches
    caches.assertRecomputed(user, "void m() { }", "void m() { new Repo() { @org.springframework.context.annotation.Bean Object z() { return null; } }; }")
    caches.assertRecomputed(user, "return null;", "return 1;")
    caches.assertRecomputed(config, "\"named\"", "\"renamed\"")
    assertNotNull(SpringBeanNames.byName(project)["renamed"])
    caches.assertRecomputed(plain, "public class Plain", "@org.springframework.stereotype.Component public class Plain")
    caches.assertRecomputed(config, "repoBean()", "repoBean2()")
    assertNotNull(SpringBeanNames.byName(project)["repoBean2"])
    caches.assertRecomputed(plain, "public class Plain", "public class Plain implements Repo")
    caches.assertRecomputed(config, "@Bean Repo repoBean2()", "Repo repoBean2()")

    val before = SpringStructureTracker.getInstance(project).modificationCount
    add("app/Added.java", "package app; @org.springframework.stereotype.Component public class Added implements Repo {}")
    assertTrue(SpringStructureTracker.getInstance(project).modificationCount > before)
    caches.assertParity()
    assertNotNull(SpringBeanNames.byName(project)["added"])

    WriteAction.run<Throwable> { impl.delete(this) }
    caches.assertParity()
    assertNull(SpringBeanNames.byName(project)["repoImpl"])
  }

  /** Refactorings and quick fixes write PSI directly, with other events than typing. */
  fun testJavaPsiWrites() {
    add("app/Repo.java", "package app; public interface Repo { void go(); }")
    val impl = add("app/Impl.java", "package app; @org.springframework.stereotype.Component public class Impl implements Repo { public void go() {} }")
    val other = add("app/Other.java", "package app; @org.springframework.stereotype.Component public class Other implements Repo { public void go() {} }")
    val config = add("app/Config.java", """
      package app;
      import org.springframework.context.annotation.Bean;
      @org.springframework.context.annotation.Configuration
      public class Config {
        @Bean Repo repoBean() { return new Impl(); }
        @Bean Repo second() { return new Impl(); }
      }
    """)
    val user = add("app/UseIt.java", """
      package app;
      @org.springframework.stereotype.Service
      public class UseIt {
        UseIt(Repo repo) {}
        void m() { int a = 1; Repo r = new Repo() { public void go() {} }; }
        void n() { int b = 1; }
        void n2() { int c = 1; Runnable q = () -> { class L2 implements Repo { public void go() {} } }; }
      }
    """)
    caches.assertParity()
    val factory = JavaPsiFacade.getElementFactory(project)
    fun PsiFile.cls(name: String) = (this as PsiJavaFile).classes.single { it.name == name }

    caches.assertPsiKept(user, "a statement replaced in a body") { file ->
      val statement = file.cls("UseIt").findMethodsByName("n", false).single().body!!.statements.single()
      statement.replace(factory.createStatementFromText("int b = 2;", null))
    }
    caches.assertPsiRecomputed(config, "a @Bean method deleted") { it.cls("Config").findMethodsByName("second", false).single().delete() }
    caches.assertPsiRecomputed(impl, "implements removed") { it.cls("Impl").implementsList!!.referenceElements.single().delete() }
    caches.assertPsiRecomputed(user, "an anonymous class replaced with a lambda") { file ->
      val anonymous = PsiTreeUtil.findChildOfType(file, PsiAnonymousClass::class.java)!!
      anonymous.parent.replace(factory.createExpressionFromText("() -> {}", anonymous))
    }
    caches.assertPsiRecomputed(other, "@Component deleted") { it.cls("Other").modifierList!!.annotations.single().delete() }
    caches.assertPsiRecomputed(user, "a local class added") { file ->
      val body = file.cls("UseIt").findMethodsByName("n", false).single().body!!
      body.add(factory.createStatementFromText("class Local implements Repo { public void go() {} }", null))
    }
    caches.assertPsiRecomputed(user, "a local class deleted") { it.cls("UseIt").findMethodsByName("n", false).single().body!!.statements.last().delete() }
    // two changes under one block in one write: the deeper one arrives folded into "contents changed" of a statement
    caches.assertPsiRecomputed(user, "a nested local class deleted next to another change") { file ->
      val body = file.cls("UseIt").findMethodsByName("n2", false).single().body!!
      body.statements.first().replace(factory.createStatementFromText("int c = 2;", null))
      PsiTreeUtil.findChildrenOfType(body, PsiClass::class.java).single { it.name == "L2" }.parent.delete()
    }
  }

  fun testFinalFieldInitializerCounts() {
    val file = add("app/Names.java", """
      package app;
      public class Names { final String name = "a"; String other = "b"; }
    """)
    caches.assertParity()
    caches.assertKept(file, "\"b\"", "\"c\"")
    // a final field with a constant initializer is a constant, and constants can name beans
    caches.assertRecomputed(file, "\"a\"", "\"z\"")
  }
}
