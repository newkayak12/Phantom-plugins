// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.terraform

import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class HclEditorTest : BasePlatformTestCase() {
  fun testStructureViewListsBlocksAndAttributes() {
    myFixture.configureByText("main.tf", "variable \"region\" {\n  default = \"x\"\n}\nresource \"aws_instance\" \"web\" {\n  ami = 1\n  lifecycle {}\n}")
    val model = HclStructureViewModel(myFixture.file as HclFile, null)
    try {
      fun names(e: TreeElement): List<Any> = e.children.map { child -> child.presentation.presentableText!! to names(child) }
      assertEquals(
        listOf(
          "variable region" to listOf("default" to emptyList<Any>()),
          "resource aws_instance web" to listOf("ami" to emptyList<Any>(), "lifecycle" to emptyList<Any>()),
        ),
        names(model.root),
      )
    } finally {
      model.dispose()
    }
  }

  fun testCommentLineUsesHash() {
    myFixture.configureByText("main.tf", "a<caret> = 1")
    myFixture.performEditorAction("CommentByLineComment")
    myFixture.checkResult("#a = 1")
  }
}
