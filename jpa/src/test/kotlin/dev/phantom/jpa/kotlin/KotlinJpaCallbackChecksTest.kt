// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa.kotlin

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.jpa.JpaStubs
import dev.phantom.jpa.KotlinStdlibJdk
import dev.phantom.jpa.core.JpaEntityGraphInspection
import dev.phantom.jpa.core.JpaEntityListenerInspection
import dev.phantom.jpa.core.JpqlInspection

/** The same checks on Kotlin entities and repositories. */
class KotlinJpaCallbackChecksTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = KotlinStdlibJdk

  override fun setUp() {
    super.setUp()
    JpaStubs.add(myFixture)
    myFixture.enableInspections(JpqlInspection::class.java, JpaEntityGraphInspection::class.java, JpaEntityListenerInspection::class.java)
  }

  private fun problems(): List<String> =
    myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).filter { it.inspectionToolId?.startsWith("Phantom") == true }.mapNotNull { it.description }.sorted()

  fun testKotlinEntityGraphsCallbacksAndQueries() {
    myFixture.configureByText("Model.kt", """
      package app

      import jakarta.persistence.*
      import org.springframework.data.jpa.repository.EntityGraph
      import org.springframework.data.jpa.repository.Query
      import org.springframework.data.repository.Repository

      @Entity
      @NamedEntityGraph(name = "Room.rates", attributeNodes = [NamedAttributeNode("rates"), NamedAttributeNode("nope")])
      class Room(@Id var id: Long? = null, var name: String = "", @OneToMany var rates: MutableList<Rate> = mutableListOf()) {
        @PrePersist
        fun touch() {}

        @PreUpdate
        fun withArgument(o: Any) {}
      }

      @Entity
      class Rate(@Id var id: Long? = null, var amount: Long = 0)

      class RoomListener {
        @PostLoad
        fun loaded(room: Any) {}

        @PostRemove
        fun none() {}
      }

      interface RoomRepository : Repository<Room, Long> {
        @EntityGraph(attributePaths = ["rates", "rates.ghost"])
        fun findByName(name: String): List<Room>

        @Query("SELECT r FROM Room r WHERE upper(q.name) = :name")
        fun search(name: String): List<Room>
      }
    """.trimIndent())
    assertEquals(listOf(
      "@PostRemove method of an entity listener must take one parameter, the entity",
      "@PreUpdate method of an entity must take no parameters",
      "Entity graph names 'nope', which is not an attribute of 'Room'",
      "Entity graph path 'rates.ghost': 'ghost' is not an attribute of 'Rate'",
      "Identification variable 'q' is not defined in this query",
    ), problems())
  }
}
