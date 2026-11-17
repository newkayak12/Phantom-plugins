// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa.kotlin

import dev.phantom.jpa.RealJdk
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.jpa.core.DerivedQueryInspection
import dev.phantom.jpa.core.PhantomJpaBundle

/** Kotlin repositories in the user's style: `interface X : JpaRepository<Entity, String>` with derived finders. */
class KotlinDerivedQueryTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  override fun setUp() {
    super.setUp()
    val runtime = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)"
    myFixture.addClass("package jakarta.persistence; $runtime public @interface Entity { String name() default \"\"; }")
    myFixture.addClass("package jakarta.persistence; $runtime public @interface Id {}")
    myFixture.addClass("package org.springframework.data.repository; public interface Repository<T, ID> {}")
    myFixture.addClass("package org.springframework.data.jpa.repository; public interface JpaRepository<T, ID> extends org.springframework.data.repository.Repository<T, ID> {}")
    myFixture.addFileToProject("app/Entities.kt", """
      package app
      @jakarta.persistence.Entity
      class TimeTableOccupancyEntity(
          @jakarta.persistence.Id val id: String,
          val restaurantId: String,
          val date: String,
          val startTime: String,
          val ticketId: String,
          val userId: String,
      )
    """.trimIndent())
    myFixture.enableInspections(DerivedQueryInspection())
  }

  fun testInspection() {
    val unknown = PhantomJpaBundle.message("derived.unknown.property", "tiketId", "TimeTableOccupancyEntity")
    myFixture.configureByText("Repo.kt", """
      package app
      import org.springframework.data.jpa.repository.JpaRepository
      interface Repo : JpaRepository<TimeTableOccupancyEntity, String> {
          fun findByRestaurantIdAndDateAndStartTimeAndTicketId(restaurantId: String, date: String, startTime: String, ticketId: String): TimeTableOccupancyEntity?
          fun findAllByRestaurantId(restaurantId: String): List<TimeTableOccupancyEntity>
          fun findByRestaurantIdAnd<error descr="$unknown">TiketId</error>(restaurantId: String, ticketId: String): List<TimeTableOccupancyEntity>
      }
    """.trimIndent())
    myFixture.checkHighlighting()
  }

  fun testCompletion() {
    myFixture.configureByText("Repo.kt", """
      package app
      interface Repo : org.springframework.data.jpa.repository.JpaRepository<TimeTableOccupancyEntity, String> {
          fun findByRestaurantIdAndSt<caret>
      }
    """.trimIndent())
    myFixture.completeBasic()
    val items = myFixture.lookupElementStrings
    // a single match is inserted directly
    if (items == null) assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("fun findByRestaurantIdAndStartTime"))
    else assertContainsElements(items, "findByRestaurantIdAndStartTime")
  }
}
