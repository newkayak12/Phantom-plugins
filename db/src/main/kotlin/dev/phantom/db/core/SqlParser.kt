// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core

import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.psi.impl.source.resolve.FileContextUtil
import com.intellij.psi.tree.IElementType

/**
 * File → statements. Reads the file's [SqlDialect] and parses each statement by its first keyword. No statement kind
 * has a grammar yet, so every statement goes to [SqlLegacyNameMarker] and the tree is the one it always built; statement
 * grammars take over one kind at a time (#164).
 */
class SqlParser : PsiParser {
  override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
    val context = SqlParserContext(builder, dialectOf(builder))
    val file = builder.mark()
    while (!builder.eof()) {
      val statement = builder.mark()
      context.statement()
      if (builder.tokenType == SqlTokens.SEMICOLON) builder.advanceLexer()
      statement.done(SqlElements.STATEMENT)
    }
    file.done(root)
    return builder.treeBuilt
  }

  private fun dialectOf(builder: PsiBuilder): SqlDialect =
    builder.getUserData(FileContextUtil.CONTAINING_FILE_KEY)?.viewProvider?.virtualFile?.getUserData(SqlDialects.KEY) ?: SqlDialects.ANY
}

/** What the statement parsers share while parsing one file. */
class SqlParserContext(val builder: PsiBuilder, val dialect: SqlDialect) {
  /** Parses one statement, up to its `;` (not consumed). */
  fun statement() {
    val parse = STATEMENTS[firstKeyword()] ?: { SqlLegacyNameMarker.markStatement(builder) }
    parse()
  }

  private fun firstKeyword(): String? = builder.tokenText?.takeIf { builder.tokenType == SqlTokens.KEYWORD }?.uppercase()

  private companion object {
    /** Statement grammars by first keyword. Empty until the first one moves off the legacy marker. */
    val STATEMENTS: Map<String, SqlParserContext.() -> Unit> = emptyMap()
  }
}
