// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa.kotlin

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.jpa.JpaStubs
import dev.phantom.jpa.KotlinStdlibJdk
import dev.phantom.jpa.core.CriteriaAttributeInspection
import dev.phantom.jpa.core.PhantomJpaBundle
import dev.phantom.jpa.core.SqlInjectionInspection

/** SQL built from values and Criteria attribute names in Kotlin (string templates, inferred lambda types). */
class KotlinWave11Test : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = KotlinStdlibJdk

  override fun setUp() {
    super.setUp()
    JpaStubs.add(myFixture)
    myFixture.enableInspections(SqlInjectionInspection::class.java, CriteriaAttributeInspection::class.java)
  }

  private fun problems(): List<String> =
    myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).filter { it.inspectionToolId?.startsWith("Phantom") == true }.mapNotNull { it.description }.sorted()

  fun testTemplatesAndCriteria() {
    myFixture.addClass("package org.springframework.jdbc.core; public interface JdbcOperations { java.util.List<java.util.Map<String, Object>> queryForList(String sql, Object... args); }")
    val pkg = "package jakarta.persistence.criteria;"
    myFixture.addClass("$pkg public interface Path<X> { <Y> Path<Y> get(String attributeName); }")
    myFixture.addClass("$pkg public interface FetchParent<Z, X> {}")
    myFixture.addClass("$pkg public interface From<Z, X> extends Path<X>, FetchParent<Z, X> {}")
    myFixture.addClass("$pkg public interface Root<X> extends From<X, X> {}")
    myFixture.configureByText("Dao.kt", """
      package app

      import jakarta.persistence.*
      import jakarta.persistence.criteria.Root
      import org.springframework.jdbc.core.JdbcOperations

      const val TABLE = "rooms"

      @Entity
      class Room(@Id var id: Long? = null, var name: String = "")

      class Dao(private val jdbc: JdbcOperations) {
        fun find(name: String, limit: Int) {
          jdbc.queryForList("select * from rooms where name = '${'$'}name'")
          jdbc.queryForList("select * from ${'$'}TABLE limit ${'$'}limit")
          jdbc.queryForList("select * from rooms where name = ?", name)
          val sql = "delete from rooms where name = " + name
          jdbc.queryForList(sql)
        }

        fun spec(root: Root<Room>) {
          root.get<String>("name")
          root.get<String>("nmae")
        }
      }
    """.trimIndent())
    assertEquals(listOf(
      PhantomJpaBundle.message("criteria.unknown.attribute", "nmae", "Room"),
      PhantomJpaBundle.message("sql.injection.message", "name"),
      PhantomJpaBundle.message("sql.injection.message", "name"),
    ), problems())
  }
}
