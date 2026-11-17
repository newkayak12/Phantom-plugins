// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core

import com.intellij.formatting.*
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.TokenType
import com.intellij.psi.impl.source.tree.TreeUtil
import com.intellij.psi.util.PsiTreeUtil

/**
 * Code → Reformat for SQL. Clauses of SELECT, INSERT, UPDATE and DELETE start lines (`FROM`, `WHERE`, joins, `GROUP
 * BY`, ...), `ON` is indented under its join, a select list or SET list of several items gets one item per line, and
 * subqueries open on their own lines. Elsewhere only spaces change: one between words, none inside `a.b`, `f(x)` and
 * before `,` `;` `)`. Line breaks the user made are kept. Letter case and literals are never touched.
 *
 * The model is flat: one block per token, with the layout decided up front by [SqlLayout].
 */
class SqlFormattingModelBuilder : FormattingModelBuilder {
  override fun createModel(formattingContext: FormattingContext): FormattingModel {
    val file = formattingContext.containingFile
    val indent = formattingContext.codeStyleSettings.getIndentOptions(SqlFileType.INSTANCE).INDENT_SIZE
    val root = SqlRootBlock(file.node, SqlLayout(file.node, indent))
    return FormattingModelProvider.createFormattingModelForPsiFile(file, root, formattingContext.codeStyleSettings)
  }
}

private class SqlLeafBlock(private val node: ASTNode, private val indent: Int) : Block {
  override fun getTextRange(): TextRange = node.textRange
  override fun getSubBlocks(): List<Block> = emptyList()
  override fun getWrap(): Wrap? = null
  override fun getIndent(): Indent = Indent.getSpaceIndent(indent)
  override fun getAlignment(): Alignment? = null
  override fun getSpacing(child1: Block?, child2: Block): Spacing? = null
  override fun getChildAttributes(newChildIndex: Int): ChildAttributes = ChildAttributes(Indent.getNoneIndent(), null)
  override fun isIncomplete(): Boolean = false
  override fun isLeaf(): Boolean = true
  val leaf: ASTNode get() = node
}

private class SqlRootBlock(private val file: ASTNode, private val layout: SqlLayout) : Block {
  private val blocks: List<Block> by lazy { layout.leaves.map { SqlLeafBlock(it, layout.decision(it).indent) } }
  override fun getTextRange(): TextRange = file.textRange
  override fun getSubBlocks(): List<Block> = blocks
  override fun getWrap(): Wrap? = null
  override fun getIndent(): Indent = Indent.getNoneIndent()
  override fun getAlignment(): Alignment? = null
  override fun getSpacing(child1: Block?, child2: Block): Spacing? {
    val d = layout.decision((child2 as? SqlLeafBlock)?.leaf ?: return null)
    return Spacing.createSpacing(d.space, d.space, d.lineFeeds, d.keepBreaks, 1)
  }
  override fun getChildAttributes(newChildIndex: Int): ChildAttributes = ChildAttributes(Indent.getNoneIndent(), null)
  override fun isIncomplete(): Boolean = false
  override fun isLeaf(): Boolean = false
}

/** How one token is placed: forced line feeds before it, spaces before it on the same line, its indent when it starts a line. */
internal data class SqlPlacement(var lineFeeds: Int = 0, var space: Int = 1, var indent: Int = 0, var keepBreaks: Boolean = true)

/** Decides the placement of every token of a SQL file in one pass. */
internal class SqlLayout(file: ASTNode, private val indentSize: Int = 4) {
  val leaves: List<ASTNode> = generateSequence(TreeUtil.findFirstLeaf(file)) { TreeUtil.nextLeaf(it) }
    .filter { it.elementType != TokenType.WHITE_SPACE && it.textLength > 0 }.toList()
  private val placements = HashMap<ASTNode, SqlPlacement>()

  fun decision(leaf: ASTNode): SqlPlacement = placements[leaf] ?: SqlPlacement()

  /** A query block (statement or subquery) or the inside of other parentheses. */
  private inner class Frame(val query: Boolean, val base: Int, val close: Int, val closesOnNewLine: Boolean) {
    var tokens = 0
    var listActive = false
    var listPending = false
    var lastClause: String? = null
    val lineIndent: Int get() = if (query) base + indentSize else base
    val childBase: Int get() = if (query) base + indentSize else base + indentSize
    val childClose: Int get() = base
  }

  init {
    val tokens = leaves.filter { it.elementType !in SqlTokens.COMMENTS }
    var stack = ArrayDeque<Frame>()
    var statement: ASTNode? = null
    var dml = false
    var unaryBefore = false
    for ((i, t) in tokens.withIndex()) {
      val st = statementOf(t)
      val d = SqlPlacement()
      placements[t] = d
      if (st !== statement || i == 0) {
        statement = st
        stack = ArrayDeque(listOf(Frame(query = true, base = 0, close = 0, closesOnNewLine = false)))
        dml = (st?.psi as? SqlStatement)?.firstKeyword in DML
        d.lineFeeds = if (i == 0) 0 else 1
        d.indent = 0
        d.space = 1
        unaryBefore = false
        countAndTrack(stack.last(), t, tokens, i, dml, first = true)
        open(stack, t)
        continue
      }
      val prev = tokens[i - 1]
      val frame = stack.last()
      d.indent = frame.lineIndent
      d.space = spaceBetween(prev, t, unaryBefore)
      if (t.elementType == SqlTokens.DOT || prev.elementType == SqlTokens.DOT) d.keepBreaks = false
      unaryBefore = isUnary(t, prev)

      if (t.elementType == SqlTokens.RPAREN && stack.size > 1) {
        val closed = stack.removeLast()
        d.indent = closed.close
        if (closed.closesOnNewLine) d.lineFeeds = 1
        stack.last().tokens++
        continue
      }
      if (prev.elementType == SqlTokens.LPAREN && frame.query && frame.tokens == 0 && frame.closesOnNewLine) {
        // the first token of a subquery: on its own line
        d.lineFeeds = 1
        d.indent = frame.base
        countAndTrack(frame, t, tokens, i, dml, first = true)
      }
      else {
        if (dml && frame.query) clause(frame, t, tokens, i, d)
        countAndTrack(frame, t, tokens, i, dml, first = false)
      }
      open(stack, t)
    }
    // comments: on their line as written, indented like the token after them
    var nextIndent = 0
    for (leaf in leaves.asReversed()) {
      if (leaf.elementType in SqlTokens.COMMENTS) placements[leaf] = SqlPlacement(0, 1, nextIndent, true)
      else nextIndent = placements[leaf]?.indent ?: 0
    }
    for ((i, leaf) in leaves.withIndex()) {
      if (i > 0 && leaves[i - 1].elementType == SqlTokens.LINE_COMMENT) placements.getOrPut(leaf) { SqlPlacement() }.lineFeeds = 1
    }
  }

  private fun open(stack: ArrayDeque<Frame>, t: ASTNode) {
    if (t.elementType != SqlTokens.LPAREN) return
    val frame = stack.last()
    val subquery = t.treeParent?.elementType == SqlElements.SUBQUERY && t.treeParent.firstChildNode === t
    stack.addLast(Frame(query = subquery, base = frame.childBase, close = frame.childClose, closesOnNewLine = subquery))
  }

  private fun statementOf(t: ASTNode): ASTNode? {
    var n: ASTNode? = t
    while (n != null && n.elementType != SqlElements.STATEMENT) n = n.treeParent
    return n
  }

  private fun word(t: ASTNode?): String? = t?.takeIf { it.elementType == SqlTokens.KEYWORD }?.text?.uppercase()

  private fun countAndTrack(frame: Frame, t: ASTNode, tokens: List<ASTNode>, i: Int, dml: Boolean, first: Boolean) {
    frame.tokens++
    if (first && dml && word(t) == "SELECT") startList(frame, tokens, i, "SELECT")
  }

  /** Clause starts get a line of their own; list items after SELECT / SET and after their commas, too. */
  private fun clause(frame: Frame, t: ASTNode, tokens: List<ASTNode>, i: Int, d: SqlPlacement) {
    val kw = word(t)
    val prev = word(tokens[i - 1])
    val next = word(tokens.getOrNull(i + 1)?.takeIf { statementOf(it) === statementOf(t) })
    val starts = when (kw) {
      null -> false
      "FROM" -> prev != "DELETE"
      "WHERE", "HAVING", "LIMIT", "RETURNING", "WINDOW", "UNION", "INTERSECT", "EXCEPT", "MINUS", "VALUES", "SELECT" -> true
      "GROUP", "ORDER" -> next == "BY"
      "SET" -> prev != "CHARACTER"
      "JOIN" -> prev !in JOIN_PREFIX && prev != "OUTER"
      in JOIN_PREFIX -> next == "JOIN" || next == "OUTER"
      "ON" -> next == "DUPLICATE"
      else -> false
    }
    when {
      starts -> {
        d.lineFeeds = 1
        d.indent = frame.base
        frame.listActive = false
        frame.listPending = false
        frame.lastClause = if (kw == "JOIN" || kw in JOIN_PREFIX) "JOIN" else kw
        if (kw == "SELECT" || kw == "SET") startList(frame, tokens, i, kw)
      }
      kw == "ON" && frame.lastClause == "JOIN" -> {
        d.lineFeeds = 1
        d.indent = frame.base + indentSize
      }
      frame.listPending && kw !in LIST_MODIFIERS -> {
        frame.listPending = false
        d.lineFeeds = 1
        d.indent = frame.base + indentSize
      }
      frame.listActive && tokens[i - 1].elementType == SqlTokens.COMMA -> {
        d.lineFeeds = 1
        d.indent = frame.base + indentSize
      }
    }
  }

  /** One item per line when the list after SELECT or SET has more than one item. */
  private fun startList(frame: Frame, tokens: List<ASTNode>, i: Int, kw: String) {
    var depth = 0
    var commas = 0
    val statement = statementOf(tokens[i])
    for (k in i + 1 until tokens.size) {
      val t = tokens[k]
      if (statementOf(t) !== statement) break
      when (t.elementType) {
        SqlTokens.LPAREN -> depth++
        SqlTokens.RPAREN -> if (depth == 0) break else depth--
        SqlTokens.COMMA -> if (depth == 0) commas++
        SqlTokens.KEYWORD -> if (depth == 0 && t.text.uppercase() in LIST_ENDS) break
      }
    }
    frame.listActive = commas > 0
    frame.listPending = commas > 0
  }

  private fun isUnary(t: ASTNode, prev: ASTNode): Boolean {
    if (t.elementType != SqlTokens.OPERATOR || (t.text != "-" && t.text != "+")) return false
    return prev.elementType == SqlTokens.LPAREN || prev.elementType == SqlTokens.COMMA || prev.elementType == SqlTokens.OPERATOR ||
           prev.elementType == SqlTokens.KEYWORD && prev.text.uppercase() !in OPERAND_KEYWORDS
  }

  private fun spaceBetween(prev: ASTNode, t: ASTNode, unaryBefore: Boolean): Int = when {
    unaryBefore -> 0
    prev.elementType == SqlTokens.LPAREN -> 0
    t.elementType == SqlTokens.RPAREN || t.elementType == SqlTokens.COMMA || t.elementType == SqlTokens.SEMICOLON -> 0
    t.elementType == SqlTokens.DOT || prev.elementType == SqlTokens.DOT -> 0
    t.text == "::" || prev.text == "::" -> 0
    t.elementType == SqlTokens.LPAREN -> when {
      // `INSERT INTO t (a, b)`, `CREATE TABLE t (...)`: a table's column list
      PsiTreeUtil.getParentOfType(prev.psi, SqlTableRef::class.java, false) != null -> 1
      prev.elementType in SqlTokens.NAMES -> 0
      prev.elementType == SqlTokens.KEYWORD -> if (prev.text.uppercase() in FUNCTION_LIKE) 0 else 1
      else -> 1
    }
    else -> 1
  }

  private companion object {
    val DML = setOf("SELECT", "WITH", "INSERT", "UPDATE", "DELETE", "REPLACE")
    val JOIN_PREFIX = setOf("LEFT", "RIGHT", "INNER", "CROSS", "FULL", "NATURAL")
    val LIST_MODIFIERS = setOf("DISTINCT", "ALL")
    val LIST_ENDS = setOf("FROM", "INTO", "WHERE", "GROUP", "ORDER", "HAVING", "LIMIT", "UNION", "EXCEPT", "INTERSECT", "MINUS", "WINDOW",
                          "RETURNING", "FOR")
    val OPERAND_KEYWORDS = setOf("END", "NULL", "TRUE", "FALSE", "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP", "CURRENT_USER")
    /** Keywords written like functions or sized types: `CAST(`, `VARCHAR(40)`, `IF(`. */
    val FUNCTION_LIKE = setOf("CAST", "CONVERT", "IF", "REPLACE", "LEFT", "RIGHT", "CHAR", "VARCHAR", "VARBINARY", "BINARY", "DECIMAL",
                              "DEC", "NUMERIC", "FLOAT", "DOUBLE", "INT", "INTEGER", "BIGINT", "SMALLINT", "TINYINT", "TIMESTAMP", "TIME",
                              "DATE", "DATETIME", "CHARACTER", "ENUM", "ROW", "ARRAY", "TRUNCATE")
  }
}
