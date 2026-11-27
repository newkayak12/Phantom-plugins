// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.grpc

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ProtoReferencesTest : BasePlatformTestCase() {
  private fun typeAtCaret(): ProtoTypeDefinition? {
    val ref = myFixture.file.findReferenceAt(myFixture.caretOffset) ?: error("no reference at caret")
    return ref.resolve() as? ProtoTypeDefinition
  }

  fun testResolvesNestedTypesFromTheInnermostScopeOutwards() {
    myFixture.configureByText(
      "a.proto",
      """
      package shop;
      message Item {}
      message Order {
        message Item {}
        It<caret>em item = 1;
      }
      """.trimIndent(),
    )
    assertEquals("shop.Order.Item", typeAtCaret()?.qualifiedName)
  }

  fun testFullyQualifiedNameSkipsTheEnclosingScope() {
    myFixture.configureByText(
      "a.proto",
      """
      package shop;
      message Item {}
      message Order {
        message Item {}
        .shop.It<caret>em item = 1;
      }
      """.trimIndent(),
    )
    assertEquals("shop.Item", typeAtCaret()?.qualifiedName)
  }

  fun testResolvesTypesAndPathsOfImportedFilesUnderAnImportRoot() {
    myFixture.addFileToProject("src/main/proto/common/v1/money.proto", "package common.v1;\nmessage Money { int64 units = 1; }")
    val text = """
      package shop.v1;
      import "common/v1/money.proto";
      message Order { common.v1.Money total = 1; }
    """.trimIndent()
    myFixture.addFileToProject("src/main/proto/shop/v1/order.proto", text)
    myFixture.configureFromTempProjectFile("src/main/proto/shop/v1/order.proto")
    myFixture.editor.caretModel.moveToOffset(text.indexOf("Money total") + 2)
    assertEquals("common.v1.Money", typeAtCaret()?.qualifiedName)
    val import = (myFixture.file as ProtoFile).imports.single()
    assertEquals("money.proto", import.resolveFile()?.name)
  }

  fun testTypeFromAFileThatIsNotImportedDoesNotResolve() {
    myFixture.addFileToProject("other.proto", "message Money {}")
    myFixture.configureByText("a.proto", "message Order { Mo<caret>ney total = 1; }")
    assertNull(typeAtCaret())
  }

  fun testFindUsagesAndRenameOfAMessage() {
    myFixture.configureByText(
      "a.proto",
      """
      package shop;
      message Mo<caret>ney {}
      message Order { Money total = 1; repeated shop.Money parts = 2; }
      service Pay { rpc Charge(Money) returns (Order); }
      """.trimIndent(),
    )
    val target = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(myFixture.caretOffset), ProtoTypeDefinition::class.java)!!
    assertEquals(3, myFixture.findUsages(target).size)
    myFixture.renameElementAtCaret("Amount")
    myFixture.checkResult(
      """
      package shop;
      message Amount {}
      message Order { Amount total = 1; repeated shop.Amount parts = 2; }
      service Pay { rpc Charge(Amount) returns (Order); }
      """.trimIndent(),
    )
  }

  fun testStructureViewListsDeclarationsInFileOrder() {
    myFixture.configureByText("a.proto", "message A { int32 x = 1; enum E { V = 0; } }\nservice S { rpc R(A) returns (A); }")
    val model = ProtoStructureViewModel(myFixture.file as ProtoFile, null)
    try {
      fun names(e: com.intellij.ide.util.treeView.smartTree.TreeElement): List<Any> =
        e.children.map { child -> child.presentation.presentableText!! to names(child) }
      assertEquals(
        listOf("A" to listOf("x" to emptyList<Any>(), "E" to listOf("V" to emptyList<Any>())), "S" to listOf("R" to emptyList<Any>())),
        names(model.root),
      )
    } finally {
      model.dispose()
    }
  }
}
