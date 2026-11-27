// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core

import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiBuilderFactory
import com.intellij.lang.PsiParser
import com.intellij.openapi.project.Project
import com.intellij.psi.TokenType
import com.intellij.psi.impl.source.resolve.FileContextUtil
import com.intellij.psi.impl.source.tree.LazyParseableElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IReparseableElementType

/**
 * File → statements. Reads the file's [SqlDialect] and parses each statement by its first keyword. No statement kind
 * has a grammar yet, so every statement goes to [SqlLegacyNameMarker]; statement grammars take over one kind at a time
 * (#164).
 *
 * A statement ends at `;`, at the delimiter of a MySQL client `DELIMITER x` command (a statement of its own), or where
 * its `;` is missing before the next one ([SqlParserContext.missingSemicolonAt]).
 */
class SqlParser : PsiParser {
  override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
    val context = SqlParserContext(builder, dialectOf(builder))
    val file = builder.mark()
    while (!builder.eof()) {
      val statement = builder.mark()
      context.statement()
      statement.done(SqlElements.STATEMENT)
    }
    file.done(root)
    return builder.treeBuilt
  }

  /** The inside of one statement, parsed alone when [SqlStatementElementType] reparses it. */
  internal fun parseStatement(root: IElementType, builder: PsiBuilder): ASTNode {
    val context = SqlParserContext(builder, dialectOf(builder))
    val statement = builder.mark()
    context.statement()
    while (!builder.eof()) builder.advanceLexer()
    statement.done(root)
    return builder.treeBuilt
  }

  private fun dialectOf(builder: PsiBuilder): SqlDialect =
    builder.getUserData(FileContextUtil.CONTAINING_FILE_KEY)?.viewProvider?.virtualFile?.getUserData(SqlDialects.KEY) ?: SqlDialects.ANY
}

/** What the statement parsers share while parsing one file. */
class SqlParserContext(val builder: PsiBuilder, val dialect: SqlDialect) {
  /** The delimiter set by the last `DELIMITER x` command, `null` while it is `;`. */
  private var delimiter: String? = null

  /** Parses one statement including its `;` or delimiter. Consumes at least one token. */
  fun statement() {
    when {
      builder.tokenType == SqlTokens.DELIMITER_COMMAND -> {
        delimiter = SqlLexer.delimiterValue(builder.tokenText!!)
        builder.advanceLexer()
      }
      delimiter != null -> delimited()
      else -> {
        body(missingSemicolonAt())
        if (builder.tokenType == SqlTokens.SEMICOLON) builder.advanceLexer()
      }
    }
  }

  /**
   * A statement under `DELIMITER x`: everything up to `x`, so a `BEGIN ... END` body keeps its `;`. Each `;`-separated
   * part gets its names marked on its own, as when the parts were statements.
   */
  private fun delimited() {
    while (!builder.eof() && builder.tokenType != SqlTokens.DELIMITER && builder.tokenType != SqlTokens.DELIMITER_COMMAND) {
      if (builder.tokenType == SqlTokens.SEMICOLON) builder.advanceLexer() else body(Int.MAX_VALUE)
    }
    if (builder.tokenType == SqlTokens.DELIMITER) builder.advanceLexer()
  }

  /** One statement without its `;`, ending before [end] (an offset) at the latest. */
  private fun body(end: Int) {
    val parse = STATEMENTS[firstKeyword()] ?: { SqlLegacyNameMarker.markStatement(builder, end) }
    parse()
  }

  /**
   * Where a `SELECT` statement whose `;` is missing ends: before a `SELECT`, or an `INSERT`/`DELETE` not followed by
   * `(`, outside parentheses and not after a set operator. `Int.MAX_VALUE` when the statement runs to its `;`.
   * `SqlSyntaxAnnotator` reports the missing `;` at the statement that follows.
   */
  private fun missingSemicolonAt(): Int {
    if (firstKeyword() != "SELECT") return Int.MAX_VALUE
    val start = builder.mark()
    var depth = 0
    var previous: String? = null
    var result = Int.MAX_VALUE
    builder.advanceLexer()
    previous = "SELECT"
    while (!builder.eof() && builder.tokenType != SqlTokens.SEMICOLON && builder.tokenType != SqlTokens.DELIMITER_COMMAND) {
      val type = builder.tokenType
      val word = firstKeyword()
      if (type == SqlTokens.LPAREN) depth++
      if (type == SqlTokens.RPAREN && depth > 0) depth--
      if (depth == 0 && previous !in SqlSyntaxCheck.BEFORE_SELECT && startsStatement(word)) {
        result = builder.currentOffset
        break
      }
      previous = word
      builder.advanceLexer()
    }
    start.rollbackTo()
    return result
  }

  private fun startsStatement(word: String?): Boolean =
    word == "SELECT" || (word == "INSERT" || word == "DELETE") && builder.lookAhead(1) != SqlTokens.LPAREN

  private fun firstKeyword(): String? = builder.tokenText?.takeIf { builder.tokenType == SqlTokens.KEYWORD }?.uppercase()

  private companion object {
    /** Statement grammars by first keyword. Empty until the first one moves off the legacy marker. */
    val STATEMENTS: Map<String, SqlParserContext.() -> Unit> = emptyMap()
  }
}

/**
 * [SqlElements.STATEMENT]: typing inside one statement reparses only that statement, when its bounds cannot move. That
 * holds for a statement closed by its own `;` after a statement closed the same way, in a file without `DELIMITER`
 * commands (their delimiter changes how the lexer reads everything below them).
 */
class SqlStatementElementType : IReparseableElementType("STATEMENT", SqlLanguage) {
  override fun createNode(text: CharSequence?): ASTNode = LazyParseableElement(this, text)

  override fun parseContents(chameleon: ASTNode): ASTNode? {
    val project = chameleon.treeParent.psi.project
    val builder = PsiBuilderFactory.getInstance().createBuilder(project, chameleon, SqlLexer(), SqlLanguage, chameleon.chars)
    return SqlParser().parseStatement(this, builder).firstChildNode
  }

  override fun isReparseable(currentNode: ASTNode, newText: CharSequence, fileLanguage: Language, project: Project): Boolean {
    if (!closed(currentNode)) return false
    var previous = currentNode.treePrev
    while (previous != null && (previous.elementType == TokenType.WHITE_SPACE || previous.elementType in SqlTokens.COMMENTS)) previous = previous.treePrev
    if (previous != null && !closed(previous)) return false
    val file = currentNode.treeParent ?: return false
    if (file.getChildren(null).any { it.firstChildNode?.elementType == SqlTokens.DELIMITER_COMMAND }) return false
    val root = SqlParser().parse(SqlElements.FILE, PsiBuilderFactory.getInstance().createBuilder(SqlParserDefinition(), SqlLexer(), newText))
    val only = root.firstChildNode ?: return false
    return only.elementType == SqlElements.STATEMENT && only.treeNext == null && closed(only)
  }

  private fun closed(node: ASTNode) = node.elementType == SqlElements.STATEMENT && node.lastChildNode?.elementType == SqlTokens.SEMICOLON
}
