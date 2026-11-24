// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.JavaCodeInsightTestFixture
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.jpa.core.JdbcAggregateBoundaryInspection
import dev.phantom.jpa.core.JdbcAggregates
import dev.phantom.jpa.core.PhantomJpaBundle

object JdbcStubs {
  private const val RUNTIME = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)"

  fun add(fixture: JavaCodeInsightTestFixture) {
    if (fixture.javaFacade.findClass("org.springframework.data.repository.Repository", com.intellij.psi.search.GlobalSearchScope.allScope(fixture.project)) == null) {
      fixture.addClass("package org.springframework.data.repository; public interface Repository<T, ID> {}")
      fixture.addClass("package org.springframework.data.repository; public interface CrudRepository<T, ID> extends Repository<T, ID> {}")
    }
    fixture.addClass("package org.springframework.data.jdbc.core.mapping; public interface AggregateReference<T, ID> {}")
    fixture.addClass("package org.springframework.data.relational.core.mapping; $RUNTIME public @interface MappedCollection { String idColumn() default \"\"; String keyColumn() default \"\"; }")
    fixture.addClass("package org.springframework.data.relational.core.mapping; $RUNTIME public @interface Sequence { String value() default \"\"; String sequence() default \"\"; }")
  }
}

/** Spring Data JDBC aggregates, in a project without JPA on the classpath. */
class JdbcAggregateTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  fun testAggregateBoundaries() {
    JdbcStubs.add(myFixture)
    myFixture.addClass("""
      package app;
      import org.springframework.data.repository.CrudRepository;
      interface Customers extends CrudRepository<Customer, Long> {}
      interface Orders extends CrudRepository<Order, Long> {}
      interface Invoices extends CrudRepository<Invoice, Long> {}
    """.trimIndent())
    myFixture.addClass("package app; public class Address { String street; }")
    myFixture.addClass("package app; public class Line { String sku; }")
    myFixture.addClass("package app; public class Customer { Long id; Address address; }")
    myFixture.addClass("package app; public class Invoice { Long id; java.util.List<Line> lines; Address billing; }")
    myFixture.configureByText("Order.java", """
      package app;
      import org.springframework.data.jdbc.core.mapping.AggregateReference;
      public class Order {
        Long id;
        Customer customer;
        AggregateReference<Customer, Long> buyer;
        java.util.Set<Line> lines;
        Kind kind;
        String note;
      }
      enum Kind { A }
    """.trimIndent())
    myFixture.enableInspections(JdbcAggregateBoundaryInspection::class.java)
    val problems = myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).filter { it.inspectionToolId?.startsWith("Phantom") == true }.mapNotNull { it.description }
    assertEquals(listOf(PhantomJpaBundle.message("jdbc.root.in.root", "Customer", "Order")), problems)

    myFixture.configureFromExistingVirtualFile(myFixture.findClass("app.Line").containingFile.virtualFile)
    val shared = myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).filter { it.inspectionToolId?.startsWith("Phantom") == true }.mapNotNull { it.description }
    assertEquals(listOf(PhantomJpaBundle.message("jdbc.several.aggregates", "Line", "Invoice, Order")), shared)
  }

  /** The roots and the project check follow Java edits; typing in a file of another language keeps them. */
  fun testCachesFollowJavaEditsOnly() {
    JdbcStubs.add(myFixture)
    val repositories = myFixture.addFileToProject("app/Repositories.java",
      "package app; interface Customers extends org.springframework.data.repository.CrudRepository<Customer, Long> {}")
    myFixture.addClass("package app; public class Customer { Long id; }")
    myFixture.addClass("package app; public class Order { Long id; }")
    val notes = myFixture.addFileToProject("notes.txt", "hello")
    val scope = GlobalSearchScope.allScope(project)
    val roots = JdbcAggregates.roots(project)
    assertEquals(listOf("app.Customer"), roots.map { it.qualifiedName })
    assertTrue(JdbcAggregates.isJdbcProject(project, scope))
    edit(notes, "hello", "hello there")
    assertSame(roots, JdbcAggregates.roots(project))
    edit(repositories, "<Customer,", "<Order,")
    assertEquals(listOf("app.Order"), JdbcAggregates.roots(project).map { it.qualifiedName })
    myFixture.addClass("package jakarta.persistence; public @interface Entity {}")
    assertFalse(JdbcAggregates.isJdbcProject(project, scope))
  }

  private fun edit(file: PsiFile, from: String, to: String) {
    val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
    WriteCommandAction.runWriteCommandAction(project) {
      val at = document.text.indexOf(from)
      document.replaceString(at, at + from.length, to)
    }
    PsiDocumentManager.getInstance(project).commitAllDocuments()
  }
}
