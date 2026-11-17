// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa.core

import com.intellij.DynamicBundle
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.psi.FileViewProvider
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey
import javax.swing.Icon

@NonNls
private const val BUNDLE = "messages.PhantomJpaBundle"

object PhantomJpaBundle : DynamicBundle(BUNDLE) {
  @Nls
  fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String = getMessage(key, *params)
}

object JpqlLanguage : Language("JPQL") {
  private fun readResolve(): Any = JpqlLanguage
  override fun isCaseSensitive(): Boolean = false
}

class JpqlFileType private constructor() : LanguageFileType(JpqlLanguage) {
  override fun getName(): String = "JPQL"
  override fun getDescription(): String = PhantomJpaBundle.message("jpql.file.type.description")
  override fun getDefaultExtension(): String = "jpql"
  override fun getIcon(): Icon? = null

  companion object {
    @JvmField
    val INSTANCE: JpqlFileType = JpqlFileType()
  }
}

class JpqlFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, JpqlLanguage) {
  override fun getFileType(): FileType = JpqlFileType.INSTANCE
  override fun toString(): String = "JPQL file"

  /** Kotlin string templates were replaced by [JpqlTokens.PLACEHOLDER]; such queries are not fully known. */
  val hasPlaceholders: Boolean get() = text.contains(JpqlTokens.PLACEHOLDER_TEXT)
}

class JpqlTokenType(@NonNls debugName: String) : IElementType(debugName, JpqlLanguage)
class JpqlElementType(@NonNls debugName: String) : IElementType(debugName, JpqlLanguage)

object JpqlTokens {
  const val PLACEHOLDER_TEXT: String = "__PH__"

  @JvmField val KEYWORD: IElementType = JpqlTokenType("KEYWORD")
  @JvmField val IDENTIFIER: IElementType = JpqlTokenType("IDENTIFIER")
  @JvmField val STRING: IElementType = JpqlTokenType("STRING")
  @JvmField val NUMBER: IElementType = JpqlTokenType("NUMBER")
  @JvmField val PARAMETER: IElementType = JpqlTokenType("PARAMETER")
  @JvmField val PLACEHOLDER: IElementType = JpqlTokenType("PLACEHOLDER")
  @JvmField val DOT: IElementType = JpqlTokenType(".")
  @JvmField val COMMA: IElementType = JpqlTokenType(",")
  @JvmField val LPAREN: IElementType = JpqlTokenType("(")
  @JvmField val RPAREN: IElementType = JpqlTokenType(")")
  @JvmField val LBRACE: IElementType = JpqlTokenType("{")
  @JvmField val RBRACE: IElementType = JpqlTokenType("}")
  @JvmField val OPERATOR: IElementType = JpqlTokenType("OPERATOR")
  @JvmField val COMMENT: IElementType = JpqlTokenType("COMMENT")

  @JvmField val COMMENTS: TokenSet = TokenSet.create(COMMENT)
  @JvmField val STRINGS: TokenSet = TokenSet.create(STRING)

  /** JPQL (Jakarta Persistence 3.1, 4.14) and common HQL keywords. Case-insensitive. */
  val KEYWORDS: Set<String> = setOf(
    "SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "AS", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "FULL", "CROSS", "FETCH",
    "ON", "WITH", "GROUP", "BY", "HAVING", "ORDER", "ASC", "DESC", "DISTINCT", "UPDATE", "SET", "DELETE", "INSERT", "INTO",
    "VALUES", "IN", "IS", "NULL", "EMPTY", "MEMBER", "OF", "LIKE", "ESCAPE", "BETWEEN", "EXISTS", "ALL", "ANY", "SOME",
    "NEW", "CASE", "WHEN", "THEN", "ELSE", "END", "TRUE", "FALSE", "TREAT", "LIMIT", "OFFSET", "FIRST", "NEXT", "ROWS",
    "ROW", "ONLY", "NULLS", "LAST", "UNION", "INTERSECT", "EXCEPT", "OBJECT",
  )
}

object JpqlElements {
  @JvmField val ENTITY_REF: IElementType = JpqlElementType("ENTITY_REF")
  @JvmField val ALIAS_DECL: IElementType = JpqlElementType("ALIAS_DECL")
  @JvmField val PATH: IElementType = JpqlElementType("PATH")
  @JvmField val PATH_SEGMENT: IElementType = JpqlElementType("PATH_SEGMENT")
  @JvmField val CLASS_REF: IElementType = JpqlElementType("CLASS_REF")
  @JvmField val FUNCTION_NAME: IElementType = JpqlElementType("FUNCTION_NAME")

  @JvmField val FILE: IFileElementType = IFileElementType(JpqlLanguage)
}
