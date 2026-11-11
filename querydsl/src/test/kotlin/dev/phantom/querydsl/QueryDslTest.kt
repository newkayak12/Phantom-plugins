// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.querydsl

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMember
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class QueryDslTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = JAVA_LATEST

  override fun setUp() {
    super.setUp()
    myFixture.addClass("package com.querydsl.core.types.dsl; public class BeanPath<T> { protected BeanPath(Class<? extends T> type, String name) {} }")
    myFixture.addClass("package com.querydsl.core.types.dsl; public class EntityPathBase<T> extends BeanPath<T> { protected EntityPathBase(Class<? extends T> type, String name) { super(type, name); } }")
    myFixture.addClass("package com.querydsl.core.types.dsl; public class StringPath {}")
    myFixture.addClass("package jakarta.persistence; public @interface Transient {}")
    myFixture.addClass("package app; public class Base { Long id; }")
    myFixture.addClass("package app; public class Order extends Base { String name; Customer customer; @jakarta.persistence.Transient String note; static int COUNT; }")
    myFixture.addClass("package app; public class Customer { String name; }")
    myFixture.addClass("""
      package app;
      import com.querydsl.core.types.dsl.*;
      public class QOrder extends EntityPathBase<Order> {
        public static final QOrder order = new QOrder("order");
        public final QBase _super = new QBase("base");
        public final StringPath id = null;
        public final StringPath name = null;
        public final QCustomer customer = null;
        public QOrder(String variable) { super(Order.class, variable); }
      }
    """.trimIndent())
    myFixture.addClass("""
      package app;
      import com.querydsl.core.types.dsl.*;
      public class QCustomer extends EntityPathBase<Customer> {
        public static final QCustomer customer = new QCustomer("customer");
        public final StringPath name = null;
        public QCustomer(String variable) { super(Customer.class, variable); }
      }
    """.trimIndent())
    myFixture.addClass("""
      package app;
      import com.querydsl.core.types.dsl.*;
      public class QBase extends BeanPath<Base> {
        public final StringPath id = null;
        public QBase(String variable) { super(Base.class, variable); }
      }
    """.trimIndent())
  }

  private fun gotoTarget(text: String): String? {
    myFixture.configureByText("Repo.java", text)
    val offset = myFixture.caretOffset
    val targets = GotoDeclarationHandler.EP_NAME.extensionList.filterIsInstance<QueryDslGotoDeclaration>().single()
      .getGotoDeclarationTargets(myFixture.file.findElementAt(offset), offset, myFixture.editor)
    return targets?.single()?.let { (it as PsiMember).containingClass?.name.let { c -> if (c == null) (it as PsiClass).name else "$c.${(it as? PsiField)?.name ?: (it as? com.intellij.psi.PsiMethod)?.name}" } }
  }

  fun testGotoGoesFromThePathToTheEntity() {
    val repo = "package app; class Repo { Object f() { return QOrder.order.customer.na<caret>me; } }"
    assertEquals("Customer.name", gotoTarget(repo))
    assertEquals("Order.customer", gotoTarget("package app; class Repo { Object f() { return QOrder.order.cust<caret>omer; } }"))
    assertEquals("Base.id", gotoTarget("package app; class Repo { Object f() { return QOrder.order.i<caret>d; } }"))
    assertEquals("Order", gotoTarget("package app; class Repo { Object f() { return QOrder.or<caret>der; } }"))
    // a field of an ordinary class is left to the platform
    assertNull(gotoTarget("package app; class Repo { Object f(Customer c) { return c.na<caret>me; } }"))
  }

  fun testFindUsagesOfAnEntityFieldFindsItsPaths() {
    myFixture.addClass("package app; class Repo { Object f() { return QOrder.order.name; } Object g() { return QCustomer.customer.name; } }")
    val order = myFixture.findClass("app.Order")
    val usages = ReferencesSearch.search(order.findFieldByName("name", false)!!).findAll().map { it.element.text }
    assertEquals(listOf("QOrder.order.name"), usages)
    val ofId = ReferencesSearch.search(myFixture.findClass("app.Base").findFieldByName("id", false)!!).findAll().size
    assertEquals(0, ofId)
    myFixture.addClass("package app; class Repo2 { Object f() { return QOrder.order.id; } }")
    assertEquals(1, ReferencesSearch.search(myFixture.findClass("app.Base").findFieldByName("id", false)!!).findAll().size)
    assertEquals(listOf("QOrder.order"), ReferencesSearch.search(order).findAll().map { it.element.text }.filter { it == "QOrder.order" }.distinct())
  }

  private fun staleWarnings(name: String, text: String): List<String> {
    myFixture.enableInspections(StaleQClassInspection())
    myFixture.configureByText(name, text)
    return myFixture.doHighlighting(com.intellij.lang.annotation.HighlightSeverity.WARNING)
      .filter { it.severity == com.intellij.lang.annotation.HighlightSeverity.WARNING }
      .map { "${text.substring(it.startOffset, it.endOffset)}: ${it.description}" }
  }

  fun testStaleQClassesAreReported() {
    myFixture.addClass("package app; public class QShop extends com.querydsl.core.types.dsl.EntityPathBase<Shop> { public final com.querydsl.core.types.dsl.StringPath name = null; public QShop() { super(Shop.class, \"shop\"); } }")
    assertEquals(
      listOf(
        "Shop: QShop still has name, which this class no longer declares: build to regenerate the Q classes",
        "email: QShop has no path for email: the Q classes are out of date, build to regenerate them",
      ),
      staleWarnings("Shop.java", "package app; public class Shop { String email; static int X; transient int y; }").sorted(),
    )
  }

  fun testUpToDateEntitiesAreQuiet() {
    assertEmpty(staleWarnings("Order.java", "package app; public class Order extends Base { String name; Customer customer; @jakarta.persistence.Transient String note; static int COUNT; }"))
  }
}
