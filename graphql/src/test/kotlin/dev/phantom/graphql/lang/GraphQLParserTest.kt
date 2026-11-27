// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.graphql.lang

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.graphql.psi.GraphQLFieldDefinition
import dev.phantom.graphql.psi.GraphQLFile
import dev.phantom.graphql.psi.GraphQLFragmentSpread
import dev.phantom.graphql.psi.GraphQLTypeReference

class GraphQLParserTest : BasePlatformTestCase() {
  private fun parse(text: String) = myFixture.configureByText("schema.graphqls", text) as GraphQLFile

  private fun errors(file: GraphQLFile) = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java).map { it.errorDescription }

  fun testSchemaDocumentParsesWithoutErrors() {
    val file = parse(
      """
      schema { query: Query mutation: Mutation }
      "A book"
      type Book implements Node & Item @key(fields: "id") {
        id: ID!
        "Its title"
        title(lang: String = "en", upper: Boolean = false): String @deprecated(reason: "use name")
        type: [Genre!]!
      }
      interface Node { id: ID! }
      enum Genre { FICTION POETRY }
      union Result = | Book | Author
      input BookInput { title: String!, tags: [String] = ["a", "b"], meta: Meta = { a: 1 } }
      scalar Date @specifiedBy(url: "https://example.com")
      directive @key(fields: String!) repeatable on OBJECT | INTERFACE
      extend type Query { books(first: Int = 10): [Book] }
      """.trimIndent(),
    )
    assertEquals(emptyList<String>(), errors(file))
    assertEquals(listOf("Book", "Node", "Genre", "Result", "BookInput", "Date"), file.typeDefinitions.map { it.name })
    assertEquals(
      listOf("Book.id", "Book.title", "Book.type", "Node.id", "Query.books"),
      file.fieldDefinitions.map { "${it.ownerTypeName}.${it.name}" },
    )
  }

  fun testExecutableDocumentParsesWithoutErrors() {
    val file = parse(
      """
      query Books(${'$'}first: Int = 10, ${'$'}lang: String!) @cached {
        books(first: ${'$'}first) { ...BookParts alias: title(lang: ${'$'}lang) ... on Book { id } type }
      }
      mutation { addBook(input: { title: "x", tags: [] }) { id } }
      { type query }
      fragment BookParts on Book { id }
      """.trimIndent(),
    )
    assertEquals(emptyList<String>(), errors(file))
    assertEquals(listOf("BookParts"), file.fragmentDefinitions.map { it.name })
    assertEquals(listOf("BookParts"), PsiTreeUtil.findChildrenOfType(file, GraphQLFragmentSpread::class.java).map { it.nameElement?.text })
  }

  fun testMissingBraceDoesNotSwallowNextDefinition() {
    val file = parse("type A {\n  a: String\n\ntype B { b: A }\n")
    assertTrue(errors(file).isNotEmpty())
    assertEquals(listOf("A", "B"), file.typeDefinitions.map { it.name })
    assertEquals(listOf("A"), PsiTreeUtil.findChildrenOfType(file.typeDefinitions[1], GraphQLTypeReference::class.java).map { it.text })
    assertEquals(listOf("a", "b"), PsiTreeUtil.findChildrenOfType(file, GraphQLFieldDefinition::class.java).map { it.name })
  }
}
