// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiField
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.jpa.core.*

/** SQL built from values, Criteria attribute names, Jakarta Data and Hibernate @Find parameters, Spring Data JDBC aggregates. */
class JpaWave11Test : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  private val runtime = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)"

  override fun setUp() {
    super.setUp()
    JpaStubs.add(myFixture)
    myFixture.enableInspections(SqlInjectionInspection::class.java, CriteriaAttributeInspection::class.java, JakartaDataMethodInspection::class.java,
                                HibernateFindInspection::class.java, JdbcAggregateBoundaryInspection::class.java, JdbcMappingInspection::class.java)
  }

  private fun problems(): List<String> =
    myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).filter { it.inspectionToolId?.startsWith("Phantom") == true }.mapNotNull { it.description }.sorted()

  private fun msg(key: String, vararg params: Any) = PhantomJpaBundle.message(key, *params)

  fun testSqlInjection() {
    myFixture.addClass("package org.springframework.jdbc.core; public interface JdbcOperations { java.util.List<java.util.Map<String, Object>> queryForList(String sql, Object... args); int update(String sql, Object... args); }")
    myFixture.addClass("package jakarta.persistence; public interface Query {}")
    myFixture.addClass("package jakarta.persistence; public interface EntityManager { Query createNativeQuery(String q); }")
    myFixture.configureByText("Dao.java", """
      package app;
      import org.springframework.jdbc.core.JdbcOperations;
      import jakarta.persistence.EntityManager;
      enum Kind { A, B }
      class Dao {
        static final String TABLE = "users";
        JdbcOperations jdbc; EntityManager em;
        void run(String name, int limit, Kind kind) {
          jdbc.queryForList("select * from users where name = '" + name + "'");
          String sql = "delete from users where name = " + name;
          jdbc.update(sql);
          em.createNativeQuery(String.format("select * from %s where n = '%s'", TABLE, name));
          jdbc.queryForList("select * from users where name = ?", name);
          jdbc.queryForList("select * from " + TABLE + " limit " + limit);
          jdbc.queryForList("select * from users where kind = '" + kind + "' or k = '" + kind.name() + "'");
          String constant = "select 1";
          jdbc.update(constant);
        }
      }
    """.trimIndent())
    assertEquals(listOf(msg("sql.injection.message", "name"), msg("sql.injection.message", "name"), msg("sql.injection.message", "name")), problems())
  }

  private fun criteriaStubs() {
    val pkg = "package jakarta.persistence.criteria;"
    myFixture.addClass("$pkg public interface Path<X> { <Y> Path<Y> get(String attributeName); }")
    myFixture.addClass("$pkg public interface FetchParent<Z, X> { <X1, Y> Fetch<X1, Y> fetch(String attributeName); }")
    myFixture.addClass("$pkg public interface Fetch<Z, X> extends FetchParent<Z, X> {}")
    myFixture.addClass("$pkg public interface From<Z, X> extends Path<X>, FetchParent<Z, X> { <X1, Y> Join<X1, Y> join(String attributeName); }")
    myFixture.addClass("$pkg public interface Join<Z, X> extends From<Z, X> {}")
    myFixture.addClass("$pkg public interface Root<X> extends From<X, X> {}")
  }

  fun testCriteriaAttributes() {
    criteriaStubs()
    myFixture.configureByText("Spec.java", """
      package app;
      import jakarta.persistence.*;
      import jakarta.persistence.criteria.*;
      @Entity class Customer { @Id Long id; String name; @OneToMany java.util.List<Order> orders; }
      @Entity class Order { @Id Long id; java.math.BigDecimal total; }
      class Spec {
        <T> void generic(Root<T> r) { r.get("whatever"); }
        void run(Root<Customer> root) {
          root.get("name");
          root.get("nmae");
          Join<Customer, Order> orders = root.join("orders");
          orders.get("total");
          orders.get("totl");
          root.fetch("orderz");
        }
      }
    """.trimIndent())
    assertEquals(listOf(msg("criteria.unknown.attribute", "nmae", "Customer"), msg("criteria.unknown.attribute", "orderz", "Customer"),
                        msg("criteria.unknown.attribute", "totl", "Order")), problems())
  }

  fun testCriteriaNavigationAndCompletion() {
    criteriaStubs()
    myFixture.configureByText("Spec.java", """
      package app;
      import jakarta.persistence.*;
      import jakarta.persistence.criteria.*;
      @Entity class Customer { @Id Long id; String name; String nickname; }
      class Spec { void run(Root<Customer> root) { root.get("na<caret>me"); } }
    """.trimIndent())
    val target = myFixture.getReferenceAtCaretPositionWithAssertion().resolve()
    assertEquals("name", (target as PsiField).name)
    myFixture.configureByText("Spec2.java", """
      package app;
      import jakarta.persistence.*;
      import jakarta.persistence.criteria.*;
      @Entity class Buyer { @Id Long id; String name; String nickname; }
      class Spec2 { void run(Root<Buyer> root) { root.get("n<caret>"); } }
    """.trimIndent())
    val variants = myFixture.completeBasic().map { it.lookupString }
    assertTrue(variants.toString(), variants.containsAll(listOf("name", "nickname")))
  }

  private fun jakartaDataStubs() {
    val pkg = "package jakarta.data.repository;"
    myFixture.addClass("$pkg $runtime public @interface Repository {}")
    myFixture.addClass("$pkg public interface DataRepository<T, K> {}")
    myFixture.addClass("$pkg public interface CrudRepository<T, K> extends DataRepository<T, K> {}")
    for (a in listOf("Find", "Delete", "Insert", "Update", "Save", "Query")) myFixture.addClass("$pkg $runtime public @interface $a { String value() default \"\"; }")
    myFixture.addClass("$pkg $runtime public @interface By { String value(); }")
    myFixture.addClass("package jakarta.data.page; public interface PageRequest {}")
  }

  fun testJakartaData() {
    jakartaDataStubs()
    myFixture.configureByText("Books.java", """
      package app;
      import jakarta.persistence.*;
      import jakarta.data.repository.*;
      import jakarta.data.page.PageRequest;
      @Entity class Book { @Id Long id; String title; int pages; }
      @Repository interface Books extends CrudRepository<Book, Long> {
        @Find java.util.List<Book> byTitle(String title, PageRequest page);
        @Find java.util.List<Book> byTitel(String titel);
        @Find java.util.Optional<Book> byPages(String pages);
        @Find Book byName(@By("title") String name);
        @Delete void remove(@By("id(this)") Long key);
        @Insert void add(Book a, Book b);
        @Save Book save(Book b);
        java.util.List<Book> findByTitle(String title);
        java.util.List<Book> findByAuthor(String author);
        @Query("from Book") java.util.List<Book> findByWhatever();
      }
    """.trimIndent())
    assertEquals(listOf(
      msg("attribute.parameter.type", "pages", "String", "pages", "int"),
      msg("attribute.parameter.unknown", "titel", "Book"),
      msg("derived.unknown.property", "author", "Book"),
      msg("jakarta.data.lifecycle.parameters", "@Insert"),
    ).sorted(), problems())
  }

  fun testJakartaDataNeedsTheLibrary() {
    myFixture.configureByText("Books.java", """
      package app;
      import jakarta.persistence.*;
      @Entity class Book { @Id Long id; }
      interface Books { java.util.List<Book> findByAuthor(String author); }
    """.trimIndent())
    assertEmpty(problems())
  }

  fun testHibernateFind() {
    myFixture.addClass("package org.hibernate.annotations.processing; $runtime public @interface Find {}")
    myFixture.addClass("package org.hibernate.query; public interface Order<X> {}")
    myFixture.configureByText("Queries.java", """
      package app;
      import jakarta.persistence.*;
      import org.hibernate.annotations.processing.Find;
      @Entity class Book { @Id Long id; String isbn; }
      interface Queries {
        @Find Book byIsbn(String isbn);
        @Find java.util.List<Book> all(org.hibernate.query.Order<Book> order);
        @Find Book byIbsn(String ibsn);
        @Find Book byId(String id);
        @Find String notAnEntity(String x);
      }
    """.trimIndent())
    assertEquals(listOf(msg("attribute.parameter.type", "id", "String", "id", "Long"), msg("attribute.parameter.unknown", "ibsn", "Book")).sorted(), problems())
  }

  private fun jdbcStubs() = JdbcStubs.add(myFixture)

  fun testJdbcMapping() {
    jdbcStubs()
    myFixture.configureByText("Order.java", """
      package app;
      import org.springframework.data.relational.core.mapping.*;
      class Order {
        @Sequence Long id;
        @Sequence("order_seq") Long number;
        @MappedCollection(keyColumn = "pos") java.util.List<Line> lines;
        @MappedCollection(keyColumn = "pos") java.util.Set<Line> tags;
      }
      class Line {}
    """.trimIndent())
    assertEquals(listOf(msg("jdbc.key.column.unordered", "Set<Line>"), msg("jdbc.sequence.empty")).sorted(), problems())
  }
}
