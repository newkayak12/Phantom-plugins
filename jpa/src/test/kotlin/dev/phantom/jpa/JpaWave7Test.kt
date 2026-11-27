// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.jpa.core.JpaEntityInspection
import dev.phantom.jpa.core.JpaEntityListenerUsageInspection
import dev.phantom.jpa.core.JpaNamedQueryInspection
import dev.phantom.jpa.core.JpqlInspection
import dev.phantom.jpa.core.LombokEntityInspection

/** Lombok constructors, entity listener usage, named query names and JPQL in EntityManager.createQuery (Java). */
class JpaWave7Test : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  override fun setUp() {
    super.setUp()
    JpaStubs.add(myFixture)
    val type = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)"
    myFixture.addClass("package jakarta.persistence; $type @java.lang.annotation.Repeatable(NamedQueries.class) public @interface NamedQuery { String name(); String query(); }")
    myFixture.addClass("package jakarta.persistence; $type public @interface NamedQueries { NamedQuery[] value(); }")
    myFixture.addClass("package jakarta.persistence; public interface Query {}")
    myFixture.addClass("package jakarta.persistence; public interface EntityManager { Query createQuery(String q); Query createNamedQuery(String name); }")
    myFixture.enableInspections(LombokEntityInspection::class.java, JpaEntityInspection::class.java, JpaEntityListenerUsageInspection::class.java,
                                JpaNamedQueryInspection::class.java, JpqlInspection::class.java)
  }

  private fun problems(): List<String> =
    myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).filter { it.inspectionToolId?.startsWith("Phantom") == true }.mapNotNull { it.description }.sorted()

  fun testLombokAllArgsWithoutNoArgs() {
    myFixture.configureByText("Entities.java", """
      package app;
      import jakarta.persistence.*;
      import lombok.*;

      @Entity @AllArgsConstructor class Bad { @Id Long id; }
      @Entity @AllArgsConstructor @NoArgsConstructor class Good { @Id Long id; }
      @Entity @AllArgsConstructor class Written { @Id Long id; Written() {} }
    """.trimIndent())
    assertEquals(listOf("@AllArgsConstructor replaces the default constructor Hibernate needs to load the entity: add @NoArgsConstructor"), problems())
  }

  private val listeners = """
    package app;
    import jakarta.persistence.*;

    class Audit { @PrePersist void before(Object o) {} }
    class Nothing { void helper() {} }
    class Injected { Injected(String clock) {} @PrePersist void before(Object o) {} }

    @Entity @EntityListeners({Audit.class, Nothing.class, Injected.class})
    class Order { @Id Long id; }
  """.trimIndent()

  fun testEntityListeners() {
    myFixture.configureByText("Order.java", listeners)
    assertEquals(listOf("Listener 'Injected' has no no-argument constructor: JPA cannot create it",
                        "Listener 'Nothing' has no lifecycle callback method (@PrePersist, @PostLoad, ...): it is never called"), problems())
  }

  fun testEntityListenersCreatedBySpring() {
    myFixture.addClass("package org.springframework.orm.hibernate5; public class SpringBeanContainer {}")
    myFixture.configureByText("Order.java", listeners)
    assertEquals(listOf("Listener 'Nothing' has no lifecycle callback method (@PrePersist, @PostLoad, ...): it is never called"), problems())
  }

  fun testNamedQueriesAndCreateQuery() {
    myFixture.addClass("""
      package app;
      @jakarta.persistence.Entity
      @jakarta.persistence.NamedQuery(name = "Item.byName", query = "select i from Item i")
      @jakarta.persistence.NamedQueries({ @jakarta.persistence.NamedQuery(name = "Item.all", query = "select i from Item i") })
      public class Item { @jakarta.persistence.Id Long id; String name; }
    """.trimIndent())
    myFixture.configureByText("Dao.java", """
      package app;
      class Dao {
        jakarta.persistence.EntityManager em;
        void run() {
          em.createNamedQuery("Item.byName");
          em.createNamedQuery("Item.all");
          em.createNamedQuery("Item.nope");
          em.createQuery("SELECT i FROM Item i WHERE x.name = 'a'");
          em.createQuery("SELECT i FROM Item i WHERE i.name = 'a'");
        }
      }
    """.trimIndent())
    assertEquals(listOf("Identification variable 'x' is not defined in this query",
                        "No @NamedQuery or @NamedNativeQuery is named 'Item.nope': createNamedQuery fails at runtime"), problems())
  }

  fun testNamedQueriesSkippedWithMappingFileFromPersistenceXml() {
    myFixture.addFileToProject("META-INF/persistence.xml",
                               "<persistence><persistence-unit name=\"u\"><mapping-file>META-INF/queries.xml</mapping-file></persistence-unit></persistence>")
    myFixture.addFileToProject("META-INF/queries.xml", "<entity-mappings/>")
    myFixture.configureByText("Dao.java", """
      package app;
      class Dao { jakarta.persistence.EntityManager em; void run() { em.createNamedQuery("Anything"); } }
    """.trimIndent())
    assertEquals(emptyList<String>(), problems())
  }

  fun testNamedQueryAddedLaterIsKnown() {
    val dao = """
      package app;
      class Dao { jakarta.persistence.EntityManager em; void run() { em.createNamedQuery("Shop.all"); } }
    """.trimIndent()
    myFixture.configureByText("Dao.java", dao)
    assertEquals(1, problems().size)
    myFixture.addClass("""
      package app;
      @jakarta.persistence.Entity @jakarta.persistence.NamedQuery(name = "Shop.all", query = "select s from Shop s")
      public class Shop { @jakarta.persistence.Id Long id; }
    """.trimIndent())
    assertEquals(emptyList<String>(), problems())
  }

  fun testNamedQueriesSkippedWithOrmXml() {
    myFixture.addFileToProject("META-INF/orm.xml", "<entity-mappings/>")
    myFixture.configureByText("Dao.java", """
      package app;
      class Dao { jakarta.persistence.EntityManager em; void run() { em.createNamedQuery("Anything"); } }
    """.trimIndent())
    assertEquals(emptyList<String>(), problems())
  }
}
