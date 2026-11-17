// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.jpa.core.JpaEntityInspection
import dev.phantom.jpa.core.JpaRepositoryLineMarkerProvider
import dev.phantom.jpa.core.ModifyingQueryInspection

/** Entity mapping checks, the @Modifying check and repository navigation on Java sources. */
class JpaMappingTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  override fun setUp() {
    super.setUp()
    JpaStubs.add(myFixture)
    myFixture.enableInspections(JpaEntityInspection::class.java, ModifyingQueryInspection::class.java)
  }

  private fun errors(): List<String> = myFixture.doHighlighting(HighlightSeverity.ERROR).mapNotNull(HighlightInfo::getDescription)

  fun testEntityWithoutId() {
    myFixture.configureByText("Plain.java", "@jakarta.persistence.Entity public class Plain { String name; }")
    assertEquals(listOf("Entity 'Plain' has no @Id or @EmbeddedId"), errors())
  }

  fun testIdFromMappedSuperclassOrEmbeddedId() {
    myFixture.addClass("package app; @jakarta.persistence.MappedSuperclass public abstract class Base { @jakarta.persistence.Id String id; }")
    myFixture.addClass("package app; public class Key { String a; }")
    myFixture.configureByText("Both.java", """
      package app;
      @jakarta.persistence.Entity class Child extends Base { String name; }
      @jakarta.persistence.Entity class Composite { @jakarta.persistence.EmbeddedId Key key; }
    """.trimIndent())
    assertEmpty(errors())
  }

  fun testMappedBy() {
    myFixture.addClass("package app; @jakarta.persistence.Entity public class Menu { @jakarta.persistence.Id String id; @jakarta.persistence.ManyToOne Restaurant restaurant; }")
    myFixture.configureByText("Restaurant.java", """
      package app;
      import jakarta.persistence.*;
      @Entity public class Restaurant {
        @Id String id;
        @OneToMany(mappedBy = "restaurant") java.util.List<Menu> menus;
        @OneToMany(mappedBy = "restaurnt") java.util.Set<Menu> typo;
        @OneToMany(mappedBy = "owner", targetEntity = Menu.class) java.util.List raw;
      }
    """.trimIndent())
    assertEquals(listOf("mappedBy 'restaurnt' is not an attribute of 'Menu'", "mappedBy 'owner' is not an attribute of 'Menu'"), errors())
  }

  fun testModifyingQuery() {
    myFixture.addClass("package app; @jakarta.persistence.Entity public class Seat { @jakarta.persistence.Id String id; boolean taken; }")
    myFixture.configureByText("SeatRepository.java", """
      package app;
      import org.springframework.data.jpa.repository.*;
      public interface SeatRepository extends JpaRepository<Seat, String> {
        String RELEASE = "update Seat s set s.taken = false";
        @Query("select s from Seat s") java.util.List<Seat> all();
        @Query("  UPDATE Seat s set s.taken = true where s.id = ?1") int take(String id);
        @Modifying @Query("delete from Seat s where s.taken = true") int clear();
        @Query(RELEASE) int releaseAll();
        @Query(value = "insert into seat(id) values (?1)", nativeQuery = true) int add(String id);
      }
    """.trimIndent())
    val messages = errors()
    assertEquals(listOf("UPDATE", "UPDATE", "INSERT"), messages.map { it.substringBefore(' ') })

    val fix = myFixture.getAllQuickFixes().first { it.text.contains("Modifying") }
    myFixture.launchAction(fix)
    assertTrue(myFixture.editor.document.text.contains("@Modifying"))
  }

  fun testRepositoryNavigation() {
    myFixture.addClass("package app; @jakarta.persistence.Entity public class Seat { @jakarta.persistence.Id String id; }")
    myFixture.addClass("package app; public interface SeatRepository extends org.springframework.data.jpa.repository.JpaRepository<Seat, String> {}")
    myFixture.addClass("package app; public interface OtherRepository extends org.springframework.data.repository.Repository<String, String> {}")
    val seat = myFixture.findClass("app.Seat")
    assertEquals(listOf("SeatRepository"), JpaRepositoryLineMarkerProvider.repositoriesOf(seat).map { it.name })

    myFixture.configureFromExistingVirtualFile(myFixture.findClass("app.SeatRepository").containingFile.virtualFile)
    myFixture.doHighlighting()
    val gutters = myFixture.findAllGutters().map { it.tooltipText }
    assertTrue(gutters.toString(), "Navigate to entity Seat" in gutters)
  }
}
