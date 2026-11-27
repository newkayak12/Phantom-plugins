// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.terraform

import com.intellij.DynamicBundle
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.icons.AllIcons
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.psi.FileViewProvider
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey
import javax.swing.Icon

@NonNls
private const val BUNDLE = "messages.TerraformBundle"

object TerraformBundle : DynamicBundle(BUNDLE) {
  @Nls
  fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String = getMessage(key, *params)
}

object TerraformLanguage : Language("PhantomTerraform") {
  private fun readResolve(): Any = TerraformLanguage
  override fun getDisplayName(): String = "Terraform"
}

class TerraformFileType private constructor() : LanguageFileType(TerraformLanguage) {
  override fun getName(): String = "Phantom Terraform"
  override fun getDisplayName(): String = "Terraform"
  override fun getDescription(): String = TerraformBundle.message("terraform.file.type.description")
  override fun getDefaultExtension(): String = "tf"
  override fun getIcon(): Icon = AllIcons.FileTypes.Text

  companion object {
    @JvmField
    val INSTANCE: TerraformFileType = TerraformFileType()
  }
}

class HclFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, TerraformLanguage) {
  override fun getFileType(): FileType = TerraformFileType.INSTANCE
  override fun toString(): String = "Terraform file"

  /** Configuration files (.tf) declare and reference objects; variable definition files (.tfvars) only assign values. */
  val isConfiguration: Boolean get() = originalFile.name.endsWith(".tf")
}

class HclTokenType(@NonNls debugName: String) : IElementType(debugName, TerraformLanguage)
class HclElementType(@NonNls debugName: String) : IElementType(debugName, TerraformLanguage)

object HclTokens {
  @JvmField val IDENT: IElementType = HclTokenType("IDENT")

  /** true, false and null. */
  @JvmField val LITERAL_KEYWORD: IElementType = HclTokenType("LITERAL_KEYWORD")
  @JvmField val FOR: IElementType = HclTokenType("for")
  @JvmField val IN: IElementType = HclTokenType("in")
  @JvmField val IF: IElementType = HclTokenType("if")
  @JvmField val NUMBER: IElementType = HclTokenType("NUMBER")
  @JvmField val QUOTE_OPEN: IElementType = HclTokenType("QUOTE_OPEN")
  @JvmField val QUOTE_CLOSE: IElementType = HclTokenType("QUOTE_CLOSE")
  @JvmField val STRING_TEXT: IElementType = HclTokenType("STRING_TEXT")

  /** `${` inside a quoted string. */
  @JvmField val INTERPOLATION_START: IElementType = HclTokenType("INTERPOLATION_START")

  /** `%{` inside a quoted string. */
  @JvmField val DIRECTIVE_START: IElementType = HclTokenType("DIRECTIVE_START")

  /** The `}` that closes an interpolation or directive. */
  @JvmField val TEMPLATE_END: IElementType = HclTokenType("TEMPLATE_END")
  @JvmField val STRIP: IElementType = HclTokenType("~")
  @JvmField val HEREDOC: IElementType = HclTokenType("HEREDOC")
  @JvmField val LINE_COMMENT: IElementType = HclTokenType("LINE_COMMENT")
  @JvmField val BLOCK_COMMENT: IElementType = HclTokenType("BLOCK_COMMENT")
  @JvmField val LBRACE: IElementType = HclTokenType("{")
  @JvmField val RBRACE: IElementType = HclTokenType("}")
  @JvmField val LPAREN: IElementType = HclTokenType("(")
  @JvmField val RPAREN: IElementType = HclTokenType(")")
  @JvmField val LBRACKET: IElementType = HclTokenType("[")
  @JvmField val RBRACKET: IElementType = HclTokenType("]")
  @JvmField val EQ: IElementType = HclTokenType("=")
  @JvmField val ARROW: IElementType = HclTokenType("=>")
  @JvmField val COLON: IElementType = HclTokenType(":")
  @JvmField val QUESTION: IElementType = HclTokenType("?")
  @JvmField val COMMA: IElementType = HclTokenType(",")
  @JvmField val DOT: IElementType = HclTokenType(".")
  @JvmField val ELLIPSIS: IElementType = HclTokenType("...")

  /** Arithmetic, comparison and logic operators, and the `*` of a splat. */
  @JvmField val OPERATOR: IElementType = HclTokenType("OPERATOR")
  @JvmField val OTHER: IElementType = HclTokenType("OTHER")

  @JvmField val COMMENTS: TokenSet = TokenSet.create(LINE_COMMENT, BLOCK_COMMENT)
  @JvmField val STRINGS: TokenSet = TokenSet.create(STRING_TEXT, HEREDOC)
  @JvmField val WORDS: TokenSet = TokenSet.create(IDENT, LITERAL_KEYWORD, FOR, IN, IF)
  @JvmField val WHITE_SPACE: TokenSet = TokenSet.create(TokenType.WHITE_SPACE)
}

object HclElements {
  @JvmField val BLOCK: IElementType = HclElementType("BLOCK")
  @JvmField val LABEL: IElementType = HclElementType("LABEL")
  @JvmField val BODY: IElementType = HclElementType("BODY")
  @JvmField val ATTRIBUTE: IElementType = HclElementType("ATTRIBUTE")
  @JvmField val LITERAL: IElementType = HclElementType("LITERAL")
  @JvmField val TEMPLATE: IElementType = HclElementType("TEMPLATE")
  @JvmField val INTERPOLATION: IElementType = HclElementType("INTERPOLATION")
  @JvmField val DIRECTIVE: IElementType = HclElementType("DIRECTIVE")
  @JvmField val TUPLE: IElementType = HclElementType("TUPLE")
  @JvmField val OBJECT: IElementType = HclElementType("OBJECT")
  @JvmField val OBJECT_ELEMENT: IElementType = HclElementType("OBJECT_ELEMENT")
  @JvmField val OBJECT_KEY: IElementType = HclElementType("OBJECT_KEY")
  @JvmField val FUNCTION_CALL: IElementType = HclElementType("FUNCTION_CALL")
  @JvmField val VARIABLE: IElementType = HclElementType("VARIABLE")
  @JvmField val GET_ATTR: IElementType = HclElementType("GET_ATTR")
  @JvmField val INDEX: IElementType = HclElementType("INDEX")
  @JvmField val SPLAT: IElementType = HclElementType("SPLAT")
  @JvmField val UNARY: IElementType = HclElementType("UNARY")
  @JvmField val BINARY: IElementType = HclElementType("BINARY")
  @JvmField val CONDITIONAL: IElementType = HclElementType("CONDITIONAL")
  @JvmField val PARENS: IElementType = HclElementType("PARENS")
  @JvmField val FOR_EXPR: IElementType = HclElementType("FOR_EXPR")
  @JvmField val FOR_VAR: IElementType = HclElementType("FOR_VAR")

  @JvmField val FILE: IFileElementType = IFileElementType(TerraformLanguage)
}
