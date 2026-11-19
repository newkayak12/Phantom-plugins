// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.jvm

import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.db.core.*
import dev.phantom.db.core.exec.*
import kotlinx.coroutines.runBlocking

/** Find usages and rename of database tables and columns across .sql files, SQL strings in Java, and JPA mappings. */
class SqlDbUsagesTest : LightJavaCodeInsightFixtureTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.db,org.jetbrains.kotlin")
    }
  }

  override fun getProjectDescriptor() = RealJdk

  private lateinit var ds: DbDataSource

  override fun setUp() {
    super.setUp()
    myFixture.addClass("""
      package org.springframework.data.jpa.repository;
      public @interface Query { String value() default ""; boolean nativeQuery() default false; }
    """.trimIndent())
    myFixture.addClass("""
      package org.springframework.jdbc.core;
      public class JdbcTemplate {
        public java.util.List<java.util.Map<String, Object>> queryForList(String sql, Object... args) { return null; }
        public int update(String sql, Object... args) { return 0; }
      }
    """.trimIndent())
    val p = "jakarta.persistence"
    myFixture.addClass("package $p; public @interface Entity { String name() default \"\"; }")
    myFixture.addClass("package $p; public @interface Id {}")
    myFixture.addClass("package $p; public @interface Table { String name() default \"\"; String schema() default \"\"; String catalog() default \"\"; }")
    myFixture.addClass("package $p; public @interface Column { String name() default \"\"; boolean nullable() default true; }")
    myFixture.addClass("package $p; public @interface ManyToOne {}")
    myFixture.addClass("package $p; public @interface JoinColumn { String name() default \"\"; String referencedColumnName() default \"\"; }")
    myFixture.addClass("package $p; public @interface JoinTable { String name() default \"\"; JoinColumn[] joinColumns() default {}; }")
    ds = DbDataSource().apply {
      name = "shop"
      dbms = Dbms.H2
      url = "jdbc:h2:mem:sql_usages_${System.nanoTime()};DB_CLOSE_DELAY=-1"
      user = "sa"
    }
    DbDataSourceService.getInstance(project).save(ds, CharArray(0))
    runBlocking {
      DbExecutor.execute(project, ds, listOf(
        "CREATE TABLE customer (id BIGINT PRIMARY KEY, name VARCHAR(40))",
        "CREATE TABLE product (id BIGINT PRIMARY KEY, name VARCHAR(40))",
        "CREATE TABLE orders (id BIGINT PRIMARY KEY, customer_name VARCHAR(40), \"ORDER\" INT)",
      ))
      for (t in SqlSchema.getInstance(project).load(ds)!!.tables) DbMetadataCache.getInstance(project).columns(ds, t)
    }
  }

  override fun tearDown() {
    try {
      DbConnectionService.getInstance(project).disconnect(ds.id)
      DbDataSourceService.getInstance(project).remove(ds.id)
    }
    catch (e: Throwable) {
      addSuppressedException(e)
    }
    finally {
      super.tearDown()
    }
  }

  private fun sql(name: String, text: String): PsiFile =
    myFixture.addFileToProject(name, text).also { DbConsoles.attach(project, it.virtualFile, ds.id) }

  private lateinit var repo: PsiFile
  private lateinit var customer: PsiFile
  private lateinit var product: PsiFile
  private lateinit var order: PsiFile

  private fun code() {
    repo = myFixture.addFileToProject("Repo.java", """
      import org.springframework.data.jpa.repository.Query;
      interface Repo {
        @Query(value = "SELECT c.name FROM customer c", nativeQuery = true) Object names();
        default void rename(org.springframework.jdbc.core.JdbcTemplate jdbc) {
          jdbc.update("UPDATE customer SET name = ? WHERE id = ?", "x", 1);
          jdbc.queryForList("SELECT name FROM product");
        }
      }
    """.trimIndent())
    customer = myFixture.addFileToProject("Customer.java", """
      import jakarta.persistence.*;
      @Entity @Table(name = "customer") class Customer {
        @Id Long id; @Column(name = "name") String name;
        @JoinTable(name = "customer_tag", joinColumns = @JoinColumn(name = "name")) java.util.List<String> tags;
      }
    """.trimIndent())
    order = myFixture.addFileToProject("Order.java", """
      import jakarta.persistence.*;
      @Entity @Table(name = "orders") class Order {
        @Id Long id; @ManyToOne @JoinColumn(name = "customer_name", referencedColumnName = "name") Customer customer;
      }
    """.trimIndent())
    product = myFixture.addFileToProject("Product.java", """
      import jakarta.persistence.*;
      @Entity @Table(name = "product") class Product { @Id Long id; @Column(name = "name") String name; }
    """.trimIndent())
  }

  private fun target(file: PsiFile, text: String): SqlDbTarget {
    val offset = file.text.indexOf(text)
    return file.findReferenceAt(offset)!!.resolve() as SqlDbTarget
  }

  fun testTableUsagesAndRenameWithoutMappings() {
    code()
    val a = sql("a.sql", "SELECT name FROM customer")
    val b = sql("b.sql", "DELETE FROM customer WHERE id = 1")
    val table = target(a, "customer")
    val files = myFixture.findUsages(table).map { it.file?.name }
    assertSameElements(files, "a.sql", "b.sql", "Repo.java", "Repo.java", "Customer.java")

    myFixture.renameElement(table, "client")
    assertEquals("SELECT name FROM client", a.text)
    assertEquals("DELETE FROM client WHERE id = 1", b.text)
    assertTrue(repo.text, repo.text.contains("\"SELECT c.name FROM client c\"") && repo.text.contains("\"UPDATE client SET name"))
    // the JPA mapping changes only when asked to
    assertTrue(customer.text.contains("@Table(name = \"customer\")"))
  }

  fun testColumnUsagesAndRenameWithMappings() {
    code()
    val a = sql("a.sql", "SELECT name FROM customer;\nSELECT name FROM product")
    val column = target(a, "name")
    assertEquals("CUSTOMER", column.table.name)
    val files = myFixture.findUsages(column).map { it.file?.name }
    // the join table's column is not the entity table's; referencedColumnName names the referenced entity's column
    assertSameElements(files, "a.sql", "Repo.java", "Repo.java", "Customer.java", "Order.java")

    SqlDbRenameOptions.set(column, SqlDbRenameOptions(updateMappings = true))
    myFixture.renameElement(column, "full_name")
    assertEquals("SELECT full_name FROM customer;\nSELECT name FROM product", a.text)
    assertTrue(repo.text, repo.text.contains("\"SELECT c.full_name FROM customer c\"") && repo.text.contains("SET full_name = ?") &&
                          repo.text.contains("\"SELECT name FROM product\""))
    assertTrue(customer.text, customer.text.contains("@Column(name = \"full_name\")"))
    assertTrue(product.text.contains("@Column(name = \"name\")"))
    assertTrue(order.text, order.text.contains("referencedColumnName = \"full_name\""))
    assertTrue(customer.text, customer.text.contains("@JoinColumn(name = \"name\")"))
  }

  /** Quoted names in strings change between their quotes; several in one string all change; Java and Kotlin escaping holds. */
  fun testRenameInsideJavaAndKotlinStrings() {
    val java = myFixture.addFileToProject("Dao.java", """
      class Dao {
        void run(org.springframework.jdbc.core.JdbcTemplate jdbc) {
          jdbc.update("UPDATE \"CUSTOMER\" SET name = ? WHERE \"CUSTOMER\".id IN (SELECT customer.id FROM customer)", "x");
        }
      }
    """.trimIndent())
    val kotlin = myFixture.addFileToProject("Repo.kt", """
      import org.springframework.data.jpa.repository.Query
      interface KRepo {
          @Query(value = "SELECT \"CUSTOMER\".id, customer.name FROM customer", nativeQuery = true)
          fun all(): List<Any>
      }
    """.trimIndent())
    val a = sql("a.sql", "SELECT name FROM customer")
    val table = target(a, "customer")
    assertEquals(listOf("Dao.java", "Dao.java", "Dao.java", "Dao.java", "Repo.kt", "Repo.kt", "Repo.kt", "a.sql"),
                 myFixture.findUsages(table).map { it.file!!.name }.sorted())
    myFixture.renameElement(table, "client")
    assertTrue(java.text, java.text.contains(""""UPDATE \"CLIENT\" SET name = ? WHERE \"CLIENT\".id IN (SELECT client.id FROM client)""""))
    assertTrue(kotlin.text, kotlin.text.contains(""""SELECT \"CLIENT\".id, client.name FROM client""""))
  }

  /** A file of another data source is neither searched by resolve nor connected to. */
  fun testFilesOfOtherDataSourcesAreNotLoaded() {
    val other = DbDataSource().apply { name = "other"; dbms = Dbms.H2; url = "jdbc:h2:mem:sql_usages_other_${System.nanoTime()};DB_CLOSE_DELAY=-1"; user = "sa" }
    DbDataSourceService.getInstance(project).save(other, CharArray(0))
    try {
      runBlocking { DbExecutor.execute(project, other, listOf("CREATE TABLE customer (id BIGINT, name VARCHAR(40))")) }
      val elsewhere = myFixture.addFileToProject("elsewhere.sql", "SELECT name FROM customer")
      DbConsoles.attach(project, elsewhere.virtualFile, other.id)
      val a = sql("a.sql", "SELECT name FROM customer")
      assertSameElements(myFixture.findUsages(target(a, "customer")).map { it.file?.name }, "a.sql")
      myFixture.renameElement(target(a, "customer"), "client")
      assertEquals("SELECT name FROM customer", elsewhere.text)
      assertNull(DbMetadataCache.getInstance(project).cachedSchemas(other.id))
    }
    finally {
      DbConnectionService.getInstance(project).disconnect(other.id)
      DbDataSourceService.getInstance(project).remove(other.id)
    }
  }

  /** Find Usages and Rename include the data source's consoles, which are under Scratches, not in the project. */
  fun testConsolesAreIncluded() {
    val console = DbConsoles.newConsoleFile(ds)!!
    com.intellij.openapi.application.WriteAction.run<Throwable> { com.intellij.openapi.vfs.VfsUtil.saveText(console, "DELETE FROM customer") }
    val a = sql("a.sql", "SELECT name FROM customer")
    val table = target(a, "customer")
    assertSameElements(myFixture.findUsages(table).map { it.file?.name }, "a.sql", console.name)
    myFixture.renameElement(table, "client")
    assertEquals("DELETE FROM client", com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(console)!!.text)
  }

  /** Expanding `*` in a Java string writes the quotes a keyword column needs escaped. */
  fun testExpandStarInAJavaString() {
    myFixture.enableInspections(SqlSelectStarInjectedInspection::class.java)
    myFixture.configureByText("Star.java", """
      class Star {
        void run(org.springframework.jdbc.core.JdbcTemplate jdbc) { jdbc.queryForList("SELECT <caret>* FROM orders"); }
      }
    """.trimIndent())
    runBlocking { DbMetadataCache.getInstance(project).columns(ds, SqlSchema.getInstance(project).load(ds)!!.find("ORDERS", null).single()) }
    myFixture.launchAction(myFixture.findSingleIntention(PhantomDbBundle.message("sql.fix.expand.star")))
    val java = com.intellij.lang.injection.InjectedLanguageManager.getInstance(project).getTopLevelFile(myFixture.file).text
    assertTrue(java, java.contains(
      """jdbc.queryForList("SELECT ID, CUSTOMER_NAME, \"ORDER\" FROM orders")"""))
  }
}
