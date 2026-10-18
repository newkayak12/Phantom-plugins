// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.jvm

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.db.core.SqlLanguage

class SqlInjectionTest : LightJavaCodeInsightFixtureTestCase() {
  companion object {
    init {
      // the same subset as the other tests in this JVM: the first test class to start the application decides
      System.setProperty("idea.load.plugins.id", "dev.phantom.db,org.jetbrains.kotlin")
    }
  }

  override fun getProjectDescriptor() = RealJdk

  override fun setUp() {
    super.setUp()
    myFixture.addClass("""
      package org.springframework.data.jpa.repository;
      public @interface Query { String value() default ""; boolean nativeQuery() default false; }
    """.trimIndent())
    myFixture.addClass("""
      package org.springframework.data.jpa.repository;
      public @interface NativeQuery { String value() default ""; }
    """.trimIndent())
    myFixture.addClass("""
      package org.springframework.jdbc.core;
      public class JdbcTemplate {
        public java.util.List<java.util.Map<String, Object>> queryForList(String sql, Object... args) { return null; }
        public int update(String sql, Object... args) { return 0; }
      }
    """.trimIndent())
  }

  /** The text of each string in the file that got SQL injected. */
  private fun injectedStrings(): List<String> {
    val manager = InjectedLanguageManager.getInstance(project)
    return PsiTreeUtil.findChildrenOfType(myFixture.file, PsiLanguageInjectionHost::class.java).mapNotNull { host ->
      manager.getInjectedPsiFiles(host)?.firstOrNull()?.first?.takeIf { it.language == SqlLanguage }?.text
    }
  }

  fun testJavaNativeQueriesAndJdbcTemplate() {
    myFixture.configureByText("Repo.java", """
      import org.springframework.data.jpa.repository.*;
      interface Repo {
        @Query(value = "SELECT * FROM reservation WHERE id = :id", nativeQuery = true) Object byId(long id);
        @Query("SELECT r FROM Reservation r") Object jpql();
        @NativeQuery("DELETE FROM reservation WHERE status = ?1") void purge(String s);
        default void run(org.springframework.jdbc.core.JdbcTemplate jdbc) {
          jdbc.update("UPDATE reservation SET status = ? WHERE id = ?", "DONE", 1);
          jdbc.queryForList("SELECT 1", "not sql");
        }
      }
    """.trimIndent())
    assertSameElements(injectedStrings(),
                       "SELECT * FROM reservation WHERE id = :id",
                       "DELETE FROM reservation WHERE status = ?1",
                       "UPDATE reservation SET status = ? WHERE id = ?",
                       "SELECT 1")
  }

  fun testKotlinRepository() {
    myFixture.configureByText("Repo.kt", ("""
      import org.springframework.data.jpa.repository.Query
      interface Repo {
          @Query(nativeQuery = true, value = ""${'"'}
              SELECT * FROM time_table
              WHERE restaurant_id = :restaurantId
          ""${'"'})
          fun tables(restaurantId: Long): List<Any>

          @Query("SELECT t FROM TimeTable t")
          fun jpql(): List<Any>

          @Query(value = "SELECT * FROM a WHERE x = '${'$'}{'$'}x'", nativeQuery = true)
          fun template(): List<Any>
      }
    """).trimIndent())
    val injected = injectedStrings()
    assertEquals(myFixture.file.language.id + " " + injected, 1, injected.size)
    assertTrue(injected[0], injected[0].contains("WHERE restaurant_id = :restaurantId"))
  }

  fun testInspectionsRunInInjectedSqlButNotInTemplates() {
    myFixture.enableInspections(dev.phantom.db.core.SqlWithoutWhereInspection::class.java, dev.phantom.db.core.SqlJoinWithoutOnInspection::class.java)
    myFixture.configureByText("Repo.java", """
      import org.springframework.data.jpa.repository.*;
      interface Repo {
        @Query(value = "UPDATE reservation SET status = 'DONE'", nativeQuery = true) void closeAll();
        @Query(value = "UPDATE reservation SET status = 'DONE' WHERE id = :id", nativeQuery = true) void close(long id);
      }
    """.trimIndent())
    val java = myFixture.doHighlighting().filter { it.inspectionToolId == "PhantomSqlWithoutWhere" }.map { it.text }
    assertEquals(listOf("UPDATE"), java)

    myFixture.configureByText("Repo.kt", ("""
      import org.springframework.data.jpa.repository.Query
      const val BY_ID = "WHERE id = :id"
      interface Repo {
          @Query(nativeQuery = true, value = "DELETE FROM reservation ${'$'}BY_ID")
          fun delete(id: Long)
      }
    """).trimIndent())
    assertEquals(listOf("DELETE FROM reservation ${'$'}BY_ID"), injectedStrings())
    assertEmpty(myFixture.doHighlighting().filter { it.inspectionToolId?.startsWith("PhantomSql") == true })
  }
}
