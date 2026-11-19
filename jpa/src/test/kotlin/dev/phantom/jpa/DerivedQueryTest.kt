// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.jpa.core.DerivedQuery
import dev.phantom.jpa.core.DerivedQueryInspection
import dev.phantom.jpa.core.PhantomJpaBundle

class DerivedQueryTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  override fun setUp() {
    super.setUp()
    val runtime = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)"
    myFixture.addClass("package jakarta.persistence; $runtime public @interface Entity { String name() default \"\"; }")
    myFixture.addClass("package jakarta.persistence; $runtime public @interface Id {}")
    myFixture.addClass("package jakarta.persistence; $runtime public @interface Embeddable {}")
    myFixture.addClass("package jakarta.persistence; $runtime public @interface ManyToOne {}")
    myFixture.addClass("package org.springframework.data.jpa.repository; $runtime public @interface Query { String value() default \"\"; }")
    myFixture.addClass("package org.springframework.data.repository; public interface Repository<T, ID> {}")
    myFixture.addClass("package org.springframework.data.repository; public interface CrudRepository<T, ID> extends Repository<T, ID> {}")
    myFixture.addClass("package org.springframework.data.domain; public interface Pageable {}")
    myFixture.addClass("package app; public class Day {}")
    myFixture.addClass("package app; @jakarta.persistence.Embeddable public class Address { String city; String zipCode; }")
    myFixture.addClass("package app; @jakarta.persistence.Entity public class Restaurant { @jakarta.persistence.Id String id; String name; }")
    myFixture.addClass("""
      package app;
      @jakarta.persistence.Entity
      public class Ticket {
        @jakarta.persistence.Id String id;
        String userId;
        app.Day date;
        int seats;
        boolean active;
        Address address;
        @jakarta.persistence.ManyToOne Restaurant restaurant;
      }
    """.trimIndent())
  }

  fun testParse() {
    val parsed = DerivedQuery.parse("findDistinctTop3ByUserIdAndDateBetweenOrActiveTrueOrderByDateDescSeatsAsc")!!
    assertEquals(listOf("UserId", "Date", "Active"), parsed.parts.map { it.propertySource })
    assertEquals(listOf(null, "Between", "True"), parsed.parts.map { it.operator })
    assertEquals(listOf(1, 2, 0), parsed.parts.map { it.arguments })
    assertEquals(listOf("Date", "Seats"), parsed.orderBy.map { it.propertySource })
    assertNull(DerivedQuery.parse("save"))
  }

  fun testValidNamesAreQuiet() {
    myFixture.enableInspections(DerivedQueryInspection())
    myFixture.configureByText("TicketRepository.java", """
      package app;
      import java.util.List;
      public interface TicketRepository extends org.springframework.data.repository.CrudRepository<Ticket, String> {
        List<Ticket> findByUserIdAndDate(String userId, app.Day date);
        List<Ticket> findAllByRestaurantIdOrderByDateDesc(String restaurantId);
        List<Ticket> findByAddressCityIgnoreCase(String city);
        List<Ticket> findByRestaurant_Name(String name, org.springframework.data.domain.Pageable page);
        boolean existsByIdEquals(String id);
        long countByActiveTrue();
        List<Ticket> findBySeatsBetween(int from, int to);
        @org.springframework.data.jpa.repository.Query("select t from Ticket t") List<Ticket> findSomethingByNothing();
        default List<Ticket> findByWhatever() { return null; }
      }
    """.trimIndent())
    myFixture.checkHighlighting()
  }

  fun testUnknownPropertyAndMissingArguments() {
    myFixture.enableInspections(DerivedQueryInspection())
    val unknown = PhantomJpaBundle.message("derived.unknown.property", "userIdd", "Ticket")
    val count = PhantomJpaBundle.message("derived.parameter.count", 2, 1)
    myFixture.configureByText("TicketRepository.java", """
      package app;
      import java.util.List;
      public interface TicketRepository extends org.springframework.data.repository.CrudRepository<Ticket, String> {
        List<Ticket> findByDateAnd<error descr="$unknown">UserIdd</error>(app.Day date, String userId);
        List<Ticket> <error descr="$count">findBySeatsBetween</error>(int from);
      }
    """.trimIndent())
    myFixture.checkHighlighting()
  }

  private fun complete(text: String): List<String> {
    myFixture.configureByText("TicketRepository.java", """
      package app;
      public interface TicketRepository extends org.springframework.data.repository.CrudRepository<Ticket, String> {
        $text
      }
    """.trimIndent())
    myFixture.completeBasic()
    return myFixture.lookupElementStrings ?: emptyList()
  }

  fun testCompletion() {
    assertContainsElements(complete("java.util.List<Ticket> findBy<caret>"), "findByUserId", "findByDate", "findByAddressCity", "findByRestaurantName")
    assertContainsElements(complete("java.util.List<Ticket> findByUserIdAndDa<caret>"), "findByUserIdAndDate")
    assertContainsElements(complete("java.util.List<Ticket> findByDate<caret>"), "findByDateBetween", "findByDateAnd", "findByDateOrderBy", "findByDateAfter")
    assertContainsElements(complete("java.util.List<Ticket> findByUserIdOrderBySe<caret>"), "findByUserIdOrderBySeatsAsc", "findByUserIdOrderBySeatsDesc")
    assertContainsElements(complete("boolean exi<caret>"), "existsBy")
  }
}
