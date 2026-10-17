// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa.kotlin

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.jpa.JpaStubs
import dev.phantom.jpa.RealJdk
import dev.phantom.jpa.core.JpaEntityInspection
import dev.phantom.jpa.core.JpaRepositoryLineMarkerProvider
import dev.phantom.jpa.core.ModifyingQueryInspection

/** The user's style: Kotlin entities with constructor properties and `@Query(CONST)` with a `const val`. */
class KotlinJpaMappingTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  override fun setUp() {
    super.setUp()
    JpaStubs.add(myFixture)
    myFixture.enableInspections(JpaEntityInspection::class.java, ModifyingQueryInspection::class.java)
    myFixture.addFileToProject("app/Entities.kt", """
      package app

      import jakarta.persistence.*

      @Entity
      class RestaurantEntity(
          @Id val id: String,
          val name: String,
      ) {
          @OneToMany(mappedBy = "restaurant")
          val menus: List<MenuEntity> = emptyList()
      }

      @Entity
      class MenuEntity(
          @Id val id: String,
          @ManyToOne val restaurant: RestaurantEntity,
      )
    """.trimIndent())
  }

  // The light fixture has no Kotlin stdlib, so compiler diagnostics ("[CODE] ...") about collections are left out
  private fun errors(): List<String> =
    myFixture.doHighlighting(HighlightSeverity.ERROR).mapNotNull(HighlightInfo::getDescription).filterNot { it.startsWith("[") }

  fun testCleanEntitiesHaveNoErrors() {
    myFixture.configureFromTempProjectFile("app/Entities.kt")
    assertEmpty(errors())
  }

  fun testMappingMistakes() {
    myFixture.configureByText("Broken.kt", """
      package app

      import jakarta.persistence.*

      @Entity
      class NoIdEntity(val name: String)

      @Entity
      class WrongSide(@Id val id: String) {
          @OneToMany(mappedBy = "restaurnt")
          val menus: List<MenuEntity> = emptyList()
      }
    """.trimIndent())
    assertEquals(listOf("Entity 'NoIdEntity' has no @Id or @EmbeddedId", "mappedBy 'restaurnt' is not an attribute of 'MenuEntity'"), errors())
  }

  fun testModifyingConstantQuery() {
    myFixture.configureByText("TimeTableJpaRepository.kt", """
      package app

      import org.springframework.data.jpa.repository.*

      const val CLAIM_SQL = ""${'"'}
          UPDATE MenuEntity m
          SET m.id = m.id
          WHERE m.id = :id
      ""${'"'}

      interface MenuJpaRepository : JpaRepository<MenuEntity, String> {
          @Modifying(clearAutomatically = true)
          @Query(CLAIM_SQL)
          fun claim(id: String): Int

          @Query(CLAIM_SQL)
          fun claimWithoutModifying(id: String): Int
      }
    """.trimIndent())
    assertEquals(1, errors().count { it.startsWith("UPDATE query needs @Modifying") })

    myFixture.launchAction(myFixture.getAllQuickFixes().first { it.text.contains("Modifying") })
    assertEquals(2, Regex("@Modifying").findAll(myFixture.editor.document.text).count())
    assertEmpty(errors().filter { "Modifying" in it })
  }

  fun testRepositoriesOfKotlinEntity() {
    myFixture.addFileToProject("app/Repositories.kt", """
      package app
      interface MenuJpaRepository : org.springframework.data.jpa.repository.JpaRepository<MenuEntity, String>
    """.trimIndent())
    val menu = myFixture.findClass("app.MenuEntity")
    assertEquals(listOf("MenuJpaRepository"), JpaRepositoryLineMarkerProvider.repositoriesOf(menu).map { it.name })

    myFixture.configureFromTempProjectFile("app/Repositories.kt")
    myFixture.doHighlighting()
    assertTrue("Navigate to entity MenuEntity" in myFixture.findAllGutters().map { it.tooltipText })
  }
}
