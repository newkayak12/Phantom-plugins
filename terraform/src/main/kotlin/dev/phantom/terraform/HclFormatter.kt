// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.terraform

import com.intellij.formatting.Alignment
import com.intellij.formatting.Block
import com.intellij.formatting.ChildAttributes
import com.intellij.formatting.FormattingContext
import com.intellij.formatting.FormattingModel
import com.intellij.formatting.FormattingModelBuilder
import com.intellij.formatting.FormattingModelProvider
import com.intellij.formatting.Indent
import com.intellij.formatting.Spacing
import com.intellij.formatting.SpacingBuilder
import com.intellij.lang.ASTNode
import com.intellij.psi.TokenType
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider
import com.intellij.psi.formatter.common.AbstractBlock
import dev.phantom.terraform.HclElements as E
import dev.phantom.terraform.HclTokens as T

/**
 * Formats like `terraform fmt` where the IDE formatter can: block bodies, objects and tuples indent by one level, `=`
 * has one space on each side and is aligned over consecutive single-line attributes or object elements, and a block's
 * type, labels and `{` are separated by one space. Strings, heredocs and comments are left as written.
 */
class HclFormattingModelBuilder : FormattingModelBuilder {
  override fun createModel(formattingContext: FormattingContext): FormattingModel {
    val settings = formattingContext.codeStyleSettings
    val block = HclFormattingBlock(formattingContext.node, null, Indent.getNoneIndent(), spacing(settings))
    return FormattingModelProvider.createFormattingModelForPsiFile(formattingContext.containingFile, block, settings)
  }

  private fun spacing(settings: CodeStyleSettings): SpacingBuilder = SpacingBuilder(settings, TerraformLanguage)
    .around(T.EQ).spaces(1)
    .around(T.ARROW).spaces(1)
    .before(T.COMMA).none()
    .after(T.COMMA).spaces(1)
    .beforeInside(E.LABEL, E.BLOCK).spaces(1)
    .beforeInside(T.LBRACE, E.BLOCK).spaces(1)
}

/** Two-space indents by default, as `terraform fmt` writes them; Settings | Editor | Code Style | Terraform changes them. */
class HclCodeStyleSettingsProvider : LanguageCodeStyleSettingsProvider() {
  override fun getLanguage() = TerraformLanguage

  override fun customizeDefaults(commonSettings: CommonCodeStyleSettings, indentOptions: CommonCodeStyleSettings.IndentOptions) {
    indentOptions.INDENT_SIZE = 2
    indentOptions.CONTINUATION_INDENT_SIZE = 2
    indentOptions.TAB_SIZE = 2
  }

  override fun getCodeSample(settingsType: SettingsType): String =
    "resource \"aws_instance\" \"web\" {\n  ami           = \"ami-1\"\n  instance_type = \"t3.micro\"\n  tags = {\n    Name = \"web\"\n  }\n}\n"
}

private class HclFormattingBlock(
  node: ASTNode,
  alignment: Alignment?,
  private val indent: Indent,
  private val spacing: SpacingBuilder,
  private val equalsAlignment: Alignment? = null,
) : AbstractBlock(node, null, alignment) {
  override fun getIndent(): Indent = indent

  override fun isLeaf(): Boolean = myNode.firstChildNode == null || myNode.elementType in LEAVES

  override fun buildChildren(): List<Block> {
    val result = ArrayList<Block>()
    var group: Alignment? = null
    for (child in children(myNode)) {
      val type = child.elementType
      group = when {
        type != E.ATTRIBUTE && type != E.OBJECT_ELEMENT -> null
        child.textContains('\n') -> null
        group == null || blankLineBefore(child) -> Alignment.createAlignment(true)
        else -> group
      }
      val childAlignment = if (type == T.EQ || type == T.COLON && myNode.elementType == E.OBJECT_ELEMENT) equalsAlignment else null
      result += HclFormattingBlock(child, childAlignment, childIndent(child), spacing, group)
    }
    return result
  }

  /** The children to format, with a block's BODY replaced by its items so they indent against the block. */
  private fun children(node: ASTNode): List<ASTNode> = node.getChildren(null).flatMap {
    when {
      it.elementType == E.BODY -> children(it)
      it.elementType == TokenType.WHITE_SPACE || it.textLength == 0 -> emptyList()
      else -> listOf(it)
    }
  }

  private fun blankLineBefore(node: ASTNode): Boolean {
    val previous = node.treePrev ?: node.treeParent?.takeIf { it.elementType == E.BODY }?.treePrev
    return previous != null && previous.elementType == TokenType.WHITE_SPACE && previous.text.count { it == '\n' } > 1
  }

  private fun childIndent(child: ASTNode): Indent {
    val parent = myNode.elementType
    val closing = child.elementType in BRACKETS
    return when {
      parent == E.BLOCK && child.treeParent.elementType == E.BODY -> Indent.getNormalIndent()
      (parent == E.OBJECT || parent == E.TUPLE || parent == E.FOR_EXPR) && !closing -> Indent.getNormalIndent()
      else -> Indent.getNoneIndent()
    }
  }

  override fun getSpacing(child1: Block?, child2: Block): Spacing? = spacing.getSpacing(this, child1, child2)

  override fun getChildAttributes(newChildIndex: Int): ChildAttributes = when (myNode.elementType) {
    E.BLOCK, E.OBJECT, E.TUPLE, E.FOR_EXPR -> ChildAttributes(Indent.getNormalIndent(), null)
    else -> ChildAttributes(Indent.getNoneIndent(), null)
  }

  private companion object {
    val LEAVES = setOf(E.TEMPLATE, E.LITERAL, E.LABEL)
    val BRACKETS = setOf(T.LBRACE, T.RBRACE, T.LBRACKET, T.RBRACKET)
  }
}
