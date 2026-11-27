// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.jpa.core.JpqlEntityRef
import dev.phantom.jpa.core.JpqlLanguage
import dev.phantom.jpa.core.JpqlPathSegment

class JpqlQueryTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  override fun setUp() {
    super.setUp()
    val runtime = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)"
    myFixture.addClass("package jakarta.persistence; $runtime public @interface Entity { String name() default \"\"; }")
    myFixture.addClass("package jakarta.persistence; $runtime public @interface Id {}")
    myFixture.addClass("package jakarta.persistence; $runtime public @interface Transient {}")
    myFixture.addClass("package jakarta.persistence; $runtime public @interface ManyToOne {}")
    myFixture.addClass("package org.springframework.data.jpa.repository; $runtime public @interface Query { String value() default \"\"; String countQuery() default \"\"; boolean nativeQuery() default false; }")
    myFixture.addClass("""
      package app;
      @jakarta.persistence.Entity
      public class TimeTable { @jakarta.persistence.Id Long id; String restaurantId; java.time.LocalDate date; }
    """.trimIndent())
    myFixture.addClass("""
      package app;
      @jakarta.persistence.Entity
      public class Item { @jakarta.persistence.Id Long id; String name; }
    """.trimIndent())
    myFixture.addClass("""
      package app;
      @jakarta.persistence.Entity(name = "Occ")
      public class Occupancy {
        @jakarta.persistence.Id String id;
        String userId;
        java.time.LocalDateTime releasedAt;
        @jakarta.persistence.ManyToOne TimeTable timeTable;
        java.util.List<Item> items;
        @jakarta.persistence.Transient String scratch;
        static final String NOT_AN_ATTRIBUTE = "x";
      }
    """.trimIndent())
  }

  private fun repository(query: String, extra: String = ""): String = """
    package app;
    import org.springframework.data.jpa.repository.Query;
    interface OccupancyRepository {
      $extra
      @Query("$query")
      java.util.List<Occupancy> find(String userId);
    }
  """.trimIndent()

  private fun errors(): List<String> = myFixture.doHighlighting(HighlightSeverity.ERROR).mapNotNull(HighlightInfo::getDescription)

  fun testQueryIsInjectedAndPathsResolve() {
    myFixture.configureByText("OccupancyRepository.java",
                              repository("SELECT o FROM Occ o WHERE o.userId = :userId AND o.timeTable.restaurant<caret>Id = :rid"))
    val reference = myFixture.getReferenceAtCaretPositionWithAssertion()
    assertEquals(JpqlLanguage, reference.element.language)
    assertInstanceOf(reference.element, JpqlPathSegment::class.java)
    val target = reference.resolve() as PsiField
    assertEquals("restaurantId", target.name)
    assertEquals("TimeTable", target.containingClass?.name)
    assertEmpty(errors())
  }

  fun testEntityNameUsesEntityAnnotationName() {
    myFixture.configureByText("OccupancyRepository.java", repository("SELECT o FROM O<caret>cc o"))
    val reference = myFixture.getReferenceAtCaretPositionWithAssertion()
    assertInstanceOf(reference.element, JpqlEntityRef::class.java)
    assertEquals("Occupancy", (reference.resolve() as PsiClass).name)
  }

  fun testJoinAliasesAndCollectionElementType() {
    myFixture.configureByText("OccupancyRepository.java",
                              repository("SELECT i.na<caret>me FROM Occ o JOIN o.items i LEFT JOIN o.timeTable AS t WHERE t.date IS NOT NULL"))
    val reference = myFixture.getReferenceAtCaretPositionWithAssertion()
    assertEquals("Item", (reference.resolve() as PsiField).containingClass?.name)
    assertEmpty(errors())
  }

  fun testUnresolvedNamesAreErrors() {
    myFixture.configureByText("OccupancyRepository.java", repository("SELECT o FROM Occ o JOIN Nope n WHERE o.nope = 1 AND o.scratch = 'a'"))
    assertSameElements(errors(), "Cannot resolve entity 'Nope'", "Cannot resolve attribute 'nope' of 'Occupancy'",
                       "Cannot resolve attribute 'scratch' of 'Occupancy'")
  }

  fun testAttributeAndEntityCompletion() {
    myFixture.configureByText("OccupancyRepository.java", repository("SELECT o FROM Occ o WHERE o.<caret>"))
    myFixture.completeBasic()
    assertSameElements(myFixture.lookupElementStrings!!, "id", "userId", "releasedAt", "timeTable", "items")

    myFixture.configureByText("OccupancyRepository.java", repository("SELECT o FROM Oc<caret>"))
    myFixture.completeBasic()
    // Completion runs in the injected editor; the injected document is the query text.
    assertEquals("SELECT o FROM Occ", myFixture.editor.document.text)
  }

  fun testJavaConcatenationIsInjectedOnce() {
    myFixture.configureByText("OccupancyRepository.java", """
      package app;
      import org.springframework.data.jpa.repository.Query;
      interface OccupancyRepository {
        String COND = "o.userId = :userId";
        @Query("SELECT o FROM Occ o " + "WHERE o.timeTable.restaurant<caret>Id = :rid")
        java.util.List<Occupancy> find(String rid);
        @Query("SELECT o FROM Occ o WHERE " + COND + " AND o.nope = 1")
        java.util.List<Occupancy> find2(String userId);
      }
    """.trimIndent())
    val target = myFixture.getReferenceAtCaretPositionWithAssertion().resolve() as PsiField
    assertEquals("TimeTable", target.containingClass?.name)
    // the constant operand is a placeholder; the second literal still belongs to the same query, reported once
    assertEquals(listOf("Cannot resolve attribute 'nope' of 'Occupancy'"), errors())
  }

  fun testNewEntityResolvesAfterJavaChange() {
    myFixture.configureByText("OccupancyRepository.java", repository("SELECT l FROM Later l"))
    assertEquals(listOf("Cannot resolve entity 'Later'"), errors())
    myFixture.addClass("package app; @jakarta.persistence.Entity public class Later { @jakarta.persistence.Id Long id; }")
    assertEmpty(errors())
  }

  fun testConstantQueryAndNativeQuery() {
    myFixture.configureByText("OccupancyRepository.java", """
      package app;
      import org.springframework.data.jpa.repository.Query;
      interface OccupancyRepository {
        String FIND = "SELECT o FROM Occ o WHERE o.user<caret>Id = :userId";
        String UNUSED = "SELECT o FROM NotInjected o";
        @Query(FIND)
        java.util.List<Occupancy> find(String userId);
        @Query(value = "SELECT * FROM occupancy WHERE nope = 1", nativeQuery = true)
        java.util.List<Occupancy> nativeFind();
      }
    """.trimIndent())
    assertEquals(JpqlLanguage, myFixture.getReferenceAtCaretPositionWithAssertion().element.language)
    // UNUSED is not used by @Query and the native query is SQL: neither is injected, so their unknown names are no errors.
    assertEmpty(errors())
  }
}
