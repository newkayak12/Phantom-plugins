// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.graphql.psi

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class GraphQLReferenceTest : BasePlatformTestCase() {
  fun testTypeReferenceResolvesAcrossFiles() {
    myFixture.addFileToProject("types.graphqls", "type Book { id: ID }")
    myFixture.configureByText("query.graphqls", "type Query { books: [Bo<caret>ok] }")
    val target = myFixture.elementAtCaret
    assertTrue(target is GraphQLTypeDefinition)
    assertEquals("types.graphqls", target.containingFile.name)
  }

  fun testExtensionAndFragmentConditionResolveToType() {
    myFixture.configureByText("a.graphqls", "type Book { id: ID }\nextend type Bo<caret>ok { title: String }")
    assertEquals("Book", (myFixture.elementAtCaret as GraphQLTypeDefinition).name)
    myFixture.configureByText("q.graphql", "fragment F on Bo<caret>ok { id }")
    assertEquals("Book", (myFixture.elementAtCaret as GraphQLTypeDefinition).name)
  }

  fun testFragmentSpreadResolves() {
    myFixture.configureByText("q.graphql", "query { books { ...Pa<caret>rts } }\nfragment Parts on Book { id }")
    assertEquals("Parts", (myFixture.elementAtCaret as GraphQLFragmentDefinition).name)
  }

  fun testBuiltInScalarIsSoft() {
    myFixture.configureByText("a.graphqls", "type Book { id: I<caret>D }")
    val reference = myFixture.file.findReferenceAt(myFixture.caretOffset)!!
    assertTrue(reference.isSoft)
    assertNull(reference.resolve())
  }

  fun testRenameTypeUpdatesReferencesInOtherFiles() {
    val other = myFixture.addFileToProject("q.graphql", "fragment F on Book { id }")
    myFixture.configureByText("a.graphqls", "type Bo<caret>ok { id: ID }\ntype Query { books: [Book!] }")
    myFixture.renameElementAtCaret("Novel")
    assertEquals("type Novel { id: ID }\ntype Query { books: [Novel!] }", myFixture.editor.document.text)
    assertEquals("fragment F on Novel { id }", other.text)
  }

  fun testFindUsagesOfType() {
    myFixture.addFileToProject("q.graphql", "fragment F on Book { id }")
    myFixture.configureByText("a.graphqls", "type Bo<caret>ok { id: ID }\ntype Query { books: [Book] }")
    assertEquals(2, myFixture.findUsages(myFixture.elementAtCaret).size)
  }

  fun testCommenterUsesHash() {
    myFixture.configureByText("a.graphqls", "type<caret> Book { id: ID }")
    myFixture.performEditorAction("CommentByLineComment")
    assertEquals("#type Book { id: ID }", myFixture.editor.document.text)
  }
}
