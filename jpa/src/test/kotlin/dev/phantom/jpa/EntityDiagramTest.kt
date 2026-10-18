// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.jpa.core.ErModel
import dev.phantom.jpa.core.ErRelation

class EntityDiagramTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  override fun setUp() {
    super.setUp()
    val runtime = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)"
    myFixture.addClass("package jakarta.persistence; $runtime public @interface Entity { String name() default \"\"; }")
    myFixture.addClass("package jakarta.persistence; $runtime public @interface Table { String name() default \"\"; }")
    myFixture.addClass("package jakarta.persistence; $runtime public @interface Id {}")
    myFixture.addClass("package jakarta.persistence; $runtime public @interface ManyToOne {}")
    myFixture.addClass("package jakarta.persistence; $runtime public @interface OneToMany { String mappedBy() default \"\"; }")
    myFixture.addClass("package jakarta.persistence; $runtime public @interface ManyToMany {}")
    myFixture.addClass("""
      package app;
      @jakarta.persistence.Entity @jakarta.persistence.Table(name = "restaurants")
      public class Restaurant {
        @jakarta.persistence.Id Long id;
        String name;
        @jakarta.persistence.OneToMany(mappedBy = "restaurant") java.util.List<TimeTable> tables;
        @jakarta.persistence.ManyToMany java.util.Set<Tag> tags;
      }
    """.trimIndent())
    myFixture.addClass("package app; @jakarta.persistence.Entity public class Tag { @jakarta.persistence.Id Long id; String label; }")
    myFixture.addFileToProject("app/TimeTable.kt", """
      package app
      import jakarta.persistence.*

      @Entity
      class TimeTable(
          @Id val id: Long,
          val startsAt: java.time.LocalTime,
          @ManyToOne val restaurant: Restaurant,
      )
    """.trimIndent())
  }

  fun testEntitiesAndRelationsFromJavaAndKotlin() {
    val model = ErModel.build(project)
    assertEquals(listOf("Restaurant", "Tag", "TimeTable"), model.entities.map { it.name })
    val restaurant = model.entities.first { it.name == "Restaurant" }
    assertEquals("restaurants", restaurant.table)
    assertEquals("id", restaurant.attributes.first().name)
    assertTrue(restaurant.attributes.first().id)
    assertEquals("time_table", model.entities.first { it.name == "TimeTable" }.table)
    // the mappedBy side is not drawn twice
    assertSameElements(model.relations,
                       ErRelation("app.Restaurant", "tags", "app.Tag", "N:M"),
                       ErRelation("app.TimeTable", "restaurant", "app.Restaurant", "N:1"))
    val mermaid = model.mermaid()
    assertTrue(mermaid, mermaid.contains("TimeTable }o--|| Restaurant : restaurant"))
    assertTrue(mermaid, mermaid.contains("Long id PK"))
  }
}
