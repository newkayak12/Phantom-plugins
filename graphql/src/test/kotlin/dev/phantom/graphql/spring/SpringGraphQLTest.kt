// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.graphql.spring

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.psi.PsiMethod
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.graphql.psi.GraphQLFieldDefinition

class SpringGraphQLTest : LightJavaCodeInsightFixtureTestCase() {
  private fun addSpringGraphQL() {
    for (name in listOf("QueryMapping", "MutationMapping", "SubscriptionMapping", "SchemaMapping", "BatchMapping")) {
      val attributes = when (name) {
        "SchemaMapping" -> "String value() default \"\"; String field() default \"\"; String typeName() default \"\";"
        "BatchMapping" -> "String value() default \"\"; String field() default \"\"; String typeName() default \"\";"
        else -> "String value() default \"\"; String name() default \"\";"
      }
      myFixture.addClass("package org.springframework.graphql.data.method.annotation; public @interface $name { $attributes }")
    }
  }

  private fun addController() {
    myFixture.addClass("public class Book {}")
    myFixture.addClass(
      """
      import org.springframework.graphql.data.method.annotation.*;
      import java.util.List;
      public class BookController {
        @QueryMapping public List<Book> books() { return null; }
        @QueryMapping(name = "bookById") public Book find(String id) { return null; }
        @MutationMapping("addBook") public Book create() { return null; }
        @SchemaMapping public String author(Book book) { return null; }
        @BatchMapping public List<Integer> rating(List<Book> books) { return null; }
      }
      """.trimIndent(),
    )
    myFixture.addClass(
      """
      import org.springframework.graphql.data.method.annotation.*;
      @SchemaMapping(typeName = "Review")
      public class ReviewController {
        @SchemaMapping(field = "text") public String body(Object review) { return null; }
      }
      """.trimIndent(),
    )
  }

  // One field per line: markers on the same line are merged into one gutter icon
  private val schema = """
    type Query {
      books: [Book]
      bookById(id: ID!): Book
    }
    type Mutation { addBook: Book }
    type Book {
      title: String
      author: String
      rating: Int
    }
    type Review { text: String }
  """.trimIndent()

  private fun handlerTargets(): List<String> = myFixture.findAllGutters().mapNotNull { gutter ->
    val info = (gutter as? LineMarkerInfo.LineMarkerGutterIconRenderer<*>)?.lineMarkerInfo as? RelatedItemLineMarkerInfo<*> ?: return@mapNotNull null
    val field = info.element?.parent?.parent as? GraphQLFieldDefinition ?: return@mapNotNull null
    "${field.ownerTypeName}.${field.name} -> " + info.createGotoRelatedItems().joinToString { (it.element as PsiMethod).name }
  }.sorted()

  fun testSchemaFieldsLinkToHandlers() {
    addSpringGraphQL()
    addController()
    myFixture.configureByText("schema.graphqls", schema)
    assertEquals(
      listOf(
        "Book.author -> author", "Book.rating -> rating", "Mutation.addBook -> create", "Query.bookById -> find",
        "Query.books -> books", "Review.text -> body",
      ),
      handlerTargets(),
    )
  }

  fun testHandlerLinksToSchemaField() {
    addSpringGraphQL()
    myFixture.addClass("public class Book {}")
    myFixture.addFileToProject("schema.graphqls", schema)
    myFixture.configureByText(
      "Q.java",
      "import org.springframework.graphql.data.method.annotation.*; class Q { @QueryMapping public Object books() { return null; } }",
    )
    val gutter = myFixture.findAllGutters().single { it.tooltipText == "Go to GraphQL schema field" }
    val info = (gutter as LineMarkerInfo.LineMarkerGutterIconRenderer<*>).lineMarkerInfo as RelatedItemLineMarkerInfo<*>
    assertEquals(listOf("books"), info.createGotoRelatedItems().map { (it.element as GraphQLFieldDefinition).name })
  }

  fun testRootFieldWithoutHandlerIsReported() {
    addSpringGraphQL()
    addController()
    myFixture.enableInspections(GraphQLUnmappedFieldInspection())
    myFixture.configureByText(
      "schema.graphqls",
      """
      type Query { books: [Book] <warning descr="No Spring GraphQL handler for Query.authors">authors</warning>: [String] }
      extend type Mutation { <warning descr="No Spring GraphQL handler for Mutation.removeBook">removeBook</warning>: Boolean }
      type Book { title: String }
      """.trimIndent(),
    )
    myFixture.checkHighlighting()
  }

  fun testNothingReportedWithoutSpringGraphQL() {
    myFixture.enableInspections(GraphQLUnmappedFieldInspection())
    myFixture.configureByText("schema.graphqls", "type Query { authors: [String] }")
    myFixture.checkHighlighting()
    assertEquals(emptyList<String>(), handlerTargets())
  }
}
