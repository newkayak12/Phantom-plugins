// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa.kotlin

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.jpa.JpaStubs
import dev.phantom.jpa.KotlinStdlibJdk
import dev.phantom.jpa.core.KotlinJpaEntityInspection

/** Non-null Kotlin properties on attributes mapped as nullable. */
class KotlinJpaNullabilityTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = KotlinStdlibJdk

  override fun setUp() {
    super.setUp()
    JpaStubs.add(myFixture)
    myFixture.enableInspections(KotlinJpaEntityInspection::class.java)
  }

  private fun problems(): List<String> =
    myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).filter { it.inspectionToolId == "PhantomKotlinJpaEntity" }
      .mapNotNull { it.description }.filter { "non-null" in it }.sorted()

  fun testNonNullPropertyOnNullableColumn() {
    myFixture.configureByText("Room.kt", """
      package app

      import jakarta.persistence.*

      @Entity
      class Hotel(@Id var id: Long? = null)

      @Entity
      class Room(
        @Id var id: Long? = null,
        @Column(nullable = true) var name: String = "",
        @Column(nullable = true) var note: String? = null,
        @Column(nullable = false) var code: String = "",
        @Column(name = "floor") var floor: Int = 0,
        @ManyToOne(optional = true) var hotel: Hotel = Hotel(),
        @ManyToOne @JoinColumn(nullable = true) var other: Hotel? = null,
      ) {
        @Column(nullable = true)
        var size: Int = 0

        @Column(nullable = true)
        var inferred = 3
      }
    """.trimIndent())
    assertEquals(listOf(
      "'hotel' is declared non-null but mapped with @ManyToOne(optional = true): a NULL in the column breaks Kotlin's null safety; make the type nullable or the column NOT NULL",
      "'name' is declared non-null but mapped with @Column(nullable = true): a NULL in the column breaks Kotlin's null safety; make the type nullable or the column NOT NULL",
      "'size' is declared non-null but mapped with @Column(nullable = true): a NULL in the column breaks Kotlin's null safety; make the type nullable or the column NOT NULL",
    ), problems())
  }
}
