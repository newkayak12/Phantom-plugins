// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.terraform

import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class HclEditorTest : BasePlatformTestCase() {
  private fun reformat(before: String, after: String) {
    myFixture.configureByText("main.tf", before)
    WriteCommandAction.runWriteCommandAction(project) { CodeStyleManager.getInstance(project).reformat(myFixture.file) }
    assertEquals(after, myFixture.editor.document.text)
  }

  fun testFormatIndentsBodiesAndAlignsEquals() {
    reformat(
      """
      resource   "aws_instance"   "web"{
      ami="ami-1"
          instance_type =   "t3.micro"

      count = 2
      tags = {
      Name = "web"
          Environment="prod"
      }
      subnets = [
      "a",
      "b" ,
      ]
      lifecycle {
      create_before_destroy=true
      }
      }
      """.trimIndent(),
      """
      resource "aws_instance" "web" {
        ami           = "ami-1"
        instance_type = "t3.micro"

        count = 2
        tags = {
          Name        = "web"
          Environment = "prod"
        }
        subnets = [
          "a",
          "b",
        ]
        lifecycle {
          create_before_destroy = true
        }
      }
      """.trimIndent(),
    )
  }

  fun testFormatLeavesStringsAndHeredocsAlone() {
    val text = "a = \"x  =  y\"\nb = <<EOT\n  keep   this\nEOT\n"
    reformat(text, text)
  }

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
