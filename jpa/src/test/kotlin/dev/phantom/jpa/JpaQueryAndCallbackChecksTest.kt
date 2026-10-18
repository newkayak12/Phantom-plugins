// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.jpa.core.JpaEntityGraphInspection
import dev.phantom.jpa.core.JpaEntityListenerInspection
import dev.phantom.jpa.core.JpqlInspection

/** JPQL variables and function arguments, entity graph attributes, and lifecycle callback signatures (Java). */
class JpaQueryAndCallbackChecksTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  override fun setUp() {
    super.setUp()
    JpaStubs.add(myFixture)
    myFixture.enableInspections(JpqlInspection::class.java, JpaEntityGraphInspection::class.java, JpaEntityListenerInspection::class.java)
    myFixture.addClass("package app; @jakarta.persistence.Entity public class Item { @jakarta.persistence.Id Long id; String name; }")
    myFixture.addClass("""
      package app;
      @jakarta.persistence.Entity
      public class Booking {
        @jakarta.persistence.Id Long id;
        String userId;
        @jakarta.persistence.ManyToOne Item item;
        java.util.List<Item> items;
      }
    """.trimIndent())
  }

  private fun problems(): List<String> =
    myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).filter { it.inspectionToolId?.startsWith("Phantom") == true }.mapNotNull { it.description }.sorted()

  private fun repository(vararg queries: String): String = """
    package app;
    import org.springframework.data.jpa.repository.Query;
    interface BookingRepository extends org.springframework.data.repository.Repository<Booking, Long> {
      ${queries.withIndex().joinToString("\n") { (i, q) -> "@Query(\"$q\") java.util.List<Booking> q$i();" }}
    }
  """.trimIndent()

  fun testJpqlUndefinedVariablesAndArguments() {
    myFixture.configureByText("BookingRepository.java", repository(
      "SELECT b FROM Booking b WHERE x.userId = 'u'",
      "SELECT lower(b.userId, b.userId), substring(b.userId) FROM Booking b",
      "SELECT count(DISTINCT b), mod(b.id, 2), locate('a', b.userId, 1), max(i.name) FROM Booking b JOIN b.items i GROUP BY b.id",
      "SELECT b FROM Booking b WHERE b.userId = app.Status.ACTIVE AND trim(LEADING 'x' FROM b.userId) = 'y'",
    ))
    assertEquals(listOf("Identification variable 'x' is not defined in this query", "lower() takes 1 argument(s), not 2",
                        "substring() takes 2–3 argument(s), not 1"), problems())
  }

  fun testEntityGraphs() {
    myFixture.configureByText("Graphs.java", """
      package app;
      import jakarta.persistence.*;
      import org.springframework.data.jpa.repository.EntityGraph;

      @Entity
      @NamedEntityGraph(name = "Order.items", attributeNodes = { @NamedAttributeNode("items"), @NamedAttributeNode("nope") })
      @NamedEntityGraphs({ @NamedEntityGraph(name = "Order.one", attributeNodes = @NamedAttributeNode("missing")) })
      class Order { @Id Long id; java.util.List<Item> items; }

      interface BookingRepository extends org.springframework.data.repository.Repository<Booking, Long> {
        @EntityGraph(attributePaths = { "item", "items.name", "item.nope", "ghost" })
        java.util.List<Booking> findByUserId(String userId);
      }
    """.trimIndent())
    assertEquals(listOf(
      "Entity graph names 'missing', which is not an attribute of 'Order'",
      "Entity graph names 'nope', which is not an attribute of 'Order'",
      "Entity graph path 'ghost': 'ghost' is not an attribute of 'Booking'",
      "Entity graph path 'item.nope': 'nope' is not an attribute of 'Item'",
    ), problems())
  }

  fun testLifecycleCallbacks() {
    myFixture.configureByText("Callbacks.java", """
      package app;
      import jakarta.persistence.*;

      @Entity @EntityListeners(Audit.class)
      class Account {
        @Id Long id;
        @PrePersist void ok() { }
        @PreUpdate void withArgument(Object o) { }
        @PostLoad int loaded() { return 1; }
        @PrePersist void again() { }
      }

      class Audit {
        @PrePersist void before(Object entity) { }
        @PostRemove void none() { }
        @PostLoad static void loaded(Object e) { }
      }
    """.trimIndent())
    assertEquals(listOf(
      "@PostLoad method must not be static",
      "@PostLoad method must return void",
      "@PostRemove method of an entity listener must take one parameter, the entity",
      "@PreUpdate method of an entity must take no parameters",
      "A second @PrePersist method in this class; 'ok' already handles the event",
    ), problems())
  }
}
