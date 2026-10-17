// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa.kotlin

import dev.phantom.jpa.RealJdk
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiField
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.jpa.core.JpqlLanguage

/** The style used in the user's repositories: `@Query(CONST)` with a `const val` raw string, sometimes templated. */
class KotlinJpqlQueryTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  override fun setUp() {
    super.setUp()
    val runtime = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)"
    myFixture.addClass("package jakarta.persistence; $runtime public @interface Entity { String name() default \"\"; }")
    myFixture.addClass("package jakarta.persistence; $runtime public @interface Id {}")
    myFixture.addClass("package jakarta.persistence; $runtime public @interface ManyToOne {}")
    myFixture.addClass("package org.springframework.data.jpa.repository; $runtime public @interface Query { String value() default \"\"; boolean nativeQuery() default false; }")
    myFixture.addFileToProject("app/Entities.kt", """
      package app

      @jakarta.persistence.Entity
      class TimeTableEntity(
          @jakarta.persistence.Id val id: String,
          val restaurantId: String,
      )

      @jakarta.persistence.Entity
      class TimeTableOccupancyEntity(
          @jakarta.persistence.Id val id: String,
          val userId: String,
          @jakarta.persistence.ManyToOne val timeTable: TimeTableEntity,
      ) {
          var releasedAt: java.time.LocalDateTime? = null
              protected set
      }
    """.trimIndent())
  }

  private fun errors(): List<String> = myFixture.doHighlighting(HighlightSeverity.ERROR).mapNotNull(HighlightInfo::getDescription)

  fun testCompanionConstantWithTemplate() {
    myFixture.configureByText("TimeTableOccupancyJpaRepository.kt", """
      package app

      import org.springframework.data.jpa.repository.Query

      interface TimeTableOccupancyJpaRepository {
          companion object {
              private const val NOT_RELEASED = "AND occupancy.releasedAt IS NULL"

              private const val LOCK_ACTIVE_OCCUPANCY_SQL = ${'"'}""
              SELECT occupancy
              FROM TimeTableOccupancyEntity occupancy
              WHERE occupancy.user<caret>Id = :userId
              ${'$'}NOT_RELEASED
              AND occupancy.timeTable.restaurantId = :restaurantId
              ""${'"'}
          }

          @Query(LOCK_ACTIVE_OCCUPANCY_SQL)
          fun lockActiveOccupancy(userId: String, restaurantId: String): List<TimeTableOccupancyEntity>
      }
    """.trimIndent())
    val reference = myFixture.getReferenceAtCaretPositionWithAssertion()
    assertEquals(JpqlLanguage, reference.element.language)
    val target = reference.resolve() as PsiField
    assertEquals("userId", target.name)
    assertEmpty(errors())
  }

  fun testInlineKotlinQueryReportsUnknownAttribute() {
    myFixture.configureByText("Repo.kt", """
      package app

      interface Repo {
          @org.springframework.data.jpa.repository.Query("SELECT o FROM TimeTableOccupancyEntity o WHERE o.timeTable.nope = 1")
          fun find(): List<TimeTableOccupancyEntity>
      }
    """.trimIndent())
    assertSameElements(errors(), "Cannot resolve attribute 'nope' of 'TimeTableEntity'")
  }

  fun testKotlinCompletionOfAttributes() {
    myFixture.configureByText("Repo.kt", """
      package app

      interface Repo {
          @org.springframework.data.jpa.repository.Query("SELECT o FROM TimeTableOccupancyEntity o WHERE o.<caret>")
          fun find(): List<TimeTableOccupancyEntity>
      }
    """.trimIndent())
    myFixture.completeBasic()
    assertContainsElements(myFixture.lookupElementStrings!!, "id", "userId", "timeTable", "releasedAt")
  }
}
