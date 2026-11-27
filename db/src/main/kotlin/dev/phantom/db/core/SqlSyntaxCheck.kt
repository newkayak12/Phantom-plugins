// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.intellij.psi.TokenType

/** A change that repairs a problem: [start] until [end] of the checked text becomes [text]. */
internal data class SqlSyntaxFix(val start: Int, val end: Int, val text: String, val label: String)

internal data class SqlSyntaxProblem(val start: Int, val end: Int, val message: String, val fix: SqlSyntaxFix? = null)

/**
 * Mistakes in the shape of a statement, found from its tokens. The parser is deliberately lax so that every dialect's
 * SQL opens; this reports only what is wrong in all of them (an error here that the database accepts is worse than one
 * it leaves to the database). A statement still being typed at the end of the text is left alone.
 */
internal object SqlSyntaxCheck {
  private class Token(val type: IElementType, val start: Int, val end: Int, val text: String) {
    val word: String = if (type == SqlTokens.KEYWORD || type == SqlTokens.IDENTIFIER) text.uppercase() else ""
    fun isKeyword(vararg words: String) = type == SqlTokens.KEYWORD && word in words
    val isName: Boolean get() = type in SqlTokens.NAMES
    val isLiteral: Boolean get() = type == SqlTokens.NUMBER || type == SqlTokens.STRING
    val isComparison: Boolean get() = type == SqlTokens.OPERATOR && text in COMPARISONS
  }

  private class Block(val open: Token?, val first: Token?) {
    var rank = 0
    var clause: String? = null
    var sawFrom = false
    var sawWhere = false
    /** The index of a word reported as a misspelled keyword: what follows it reads as what that keyword takes. */
    var typoAt = -1
    val isSelect: Boolean get() = first?.isKeyword("SELECT") == true
    val isQuery: Boolean get() = first?.isKeyword("SELECT", "WITH") == true
  }

  fun check(text: CharSequence): List<SqlSyntaxProblem> {
    val out = ArrayList<SqlSyntaxProblem>()
    val tokens = tokens(text, out)
    val blocks = ArrayList<Block>().apply { add(Block(null, tokens.firstOrNull())) }
    for ((i, t) in tokens.withIndex()) {
      val block = blocks.last()
      val prev = tokens.getOrNull(i - 1)
      val next = tokens.getOrNull(i + 1)
      when {
        t.type == SqlTokens.LPAREN -> blocks.add(Block(t, next))
        t.type == SqlTokens.RPAREN -> if (blocks.size == 1) out += SqlSyntaxProblem(t.start, t.end, PhantomDbBundle.message("sql.syntax.unmatched.close")) else blocks.removeLast()
        t.type == SqlTokens.COMMA -> comma(t, prev, next, out)
        t.type == SqlTokens.KEYWORD -> keyword(t, prev, next, block, i, out)
        t.type == SqlTokens.IDENTIFIER -> identifier(t, prev, next, block, i, tokens, out)
      }
    }
    // a parenthesis open at the end while it is the last thing being typed is reported too: the statement cannot run
    blocks.drop(1).forEach { b -> b.open?.let { out += SqlSyntaxProblem(it.start, it.end, PhantomDbBundle.message("sql.syntax.unclosed.paren")) } }
    return out
  }

  private fun tokens(text: CharSequence, out: MutableList<SqlSyntaxProblem>): List<Token> {
    val lexer = SqlLexer()
    lexer.start(text)
    val tokens = ArrayList<Token>()
    while (true) {
      val type = lexer.tokenType ?: break
      val token = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
      if (unterminated(type, token)) out += SqlSyntaxProblem(lexer.tokenStart, minOf(lexer.tokenStart + 1, lexer.tokenEnd), PhantomDbBundle.message("sql.syntax.unterminated"))
      if (type != TokenType.WHITE_SPACE && type !in SqlTokens.COMMENTS) tokens += Token(type, lexer.tokenStart, lexer.tokenEnd, token)
      lexer.advance()
    }
    return tokens
  }

  private fun unterminated(type: IElementType, text: String): Boolean = when (type) {
    SqlTokens.BLOCK_COMMENT -> text.length < 4 || !text.endsWith("*/")
    SqlTokens.STRING -> {
      val body = text.dropWhile { it != '\'' && it != '$' }
      if (body.startsWith("$")) body.substring(0, body.indexOf('$', 1).let { if (it < 0) body.length - 1 else it } + 1).let { tag -> body.length < 2 * tag.length || !body.endsWith(tag) }
      else body.length < 2 || !body.endsWith("'")
    }
    SqlTokens.QUOTED_IDENTIFIER -> text.length < 2 || text.last() != closing(text[0])
    else -> false
  }

  private fun closing(open: Char) = if (open == '[') ']' else open

  private fun comma(t: Token, prev: Token?, next: Token?, out: MutableList<SqlSyntaxProblem>) {
    val remove = SqlSyntaxFix(t.start, t.end, "", PhantomDbBundle.message("sql.syntax.fix.remove.comma"))
    when {
      prev?.type == SqlTokens.COMMA -> out += SqlSyntaxProblem(t.start, t.end, PhantomDbBundle.message("sql.syntax.extra.comma"), remove)
      prev?.type == SqlTokens.LPAREN || prev?.isKeyword("SELECT") == true -> out += SqlSyntaxProblem(t.start, t.end, PhantomDbBundle.message("sql.syntax.extra.comma"), remove)
      next == null -> {}
      next.type == SqlTokens.RPAREN || next.type == SqlTokens.SEMICOLON || next.isKeyword(*AFTER_LIST) ->
        out += SqlSyntaxProblem(t.start, t.end, PhantomDbBundle.message("sql.syntax.trailing.comma"), remove)
    }
  }

  private fun keyword(t: Token, prev: Token?, next: Token?, block: Block, index: Int, out: MutableList<SqlSyntaxProblem>) {
    if (t.isKeyword("WHERE", "ON", "HAVING")) block.clause = "CONDITION"
    if (t.isKeyword("GROUP", "ORDER", "LIMIT", "SELECT", "UNION", "EXCEPT", "INTERSECT", "MINUS")) block.clause = t.word
    if (t.isKeyword("UNION", "EXCEPT", "INTERSECT", "MINUS")) {
      block.rank = 0
      block.sawFrom = false
      block.sawWhere = false
    }
    if (t.isKeyword("WHERE")) block.sawWhere = true
    if (t.isKeyword("FROM") && prev?.isKeyword("DISTINCT") != true) {
      block.sawFrom = true
      block.clause = "FROM"
    }
    val empty = next != null && (next.type == SqlTokens.SEMICOLON || next.type == SqlTokens.RPAREN || next.isKeyword(*CLAUSES))
    if (empty && t.isKeyword("FROM", "JOIN", "WHERE", "HAVING") && !(t.isKeyword("FROM") && prev?.isKeyword("DISTINCT") == true)) {
      out += SqlSyntaxProblem(t.start, t.end, PhantomDbBundle.message(if (t.isKeyword("FROM", "JOIN")) "sql.syntax.table.expected" else "sql.syntax.condition.expected", t.word))
    }
    if (block.isQuery && t.isKeyword("GROUP", "ORDER") && next != null && !next.isKeyword("BY") && next.type != SqlTokens.LPAREN && prev?.word != "WITHIN") {
      out += SqlSyntaxProblem(t.start, t.end, PhantomDbBundle.message("sql.syntax.by.expected", t.word), SqlSyntaxFix(t.end, t.end, " BY", PhantomDbBundle.message("sql.syntax.fix.add", "BY")))
    }
    if (block.isSelect) clauseOrder(t, prev, next, block, out)
    if (block.isSelect && index > 0 && block.open == null && (t.isKeyword("SELECT") || (t.isKeyword("INSERT", "DELETE") && next?.type != SqlTokens.LPAREN)) &&
        prev?.isKeyword(*BEFORE_SELECT) != true && prev != null) {
      out += SqlSyntaxProblem(t.start, t.end, PhantomDbBundle.message("sql.syntax.missing.semicolon"), SqlSyntaxFix(prev.end, prev.end, ";", PhantomDbBundle.message("sql.syntax.fix.add", ";")))
    }
  }

  private fun clauseOrder(t: Token, prev: Token?, next: Token?, block: Block, out: MutableList<SqlSyntaxProblem>) {
    if (t.isKeyword("FROM") && prev?.isKeyword("DISTINCT") == true) return
    if (t.isKeyword("GROUP", "ORDER") && (next?.isKeyword("BY") != true || prev?.word == "WITHIN")) return
    val rank = RANK[t.word]?.takeIf { t.type == SqlTokens.KEYWORD } ?: return
    if (rank < block.rank) out += SqlSyntaxProblem(t.start, t.end, PhantomDbBundle.message("sql.syntax.clause.order", t.word, NAMES.getValue(block.rank)))
    block.rank = maxOf(block.rank, rank)
  }

  private fun identifier(t: Token, prev: Token?, next: Token?, block: Block, index: Int, tokens: List<Token>, out: MutableList<SqlSyntaxProblem>) {
    if (prev?.type == SqlTokens.DOT || next?.type == SqlTokens.DOT || block.typoAt == index - 1 && index > 0) return
    typo(t, prev, next, block, index, tokens)?.let { word ->
      block.typoAt = index
      out += SqlSyntaxProblem(t.start, t.end, PhantomDbBundle.message("sql.syntax.typo", t.text, word), SqlSyntaxFix(t.start, t.end, word, PhantomDbBundle.message("sql.syntax.fix.replace", word)))
      return
    }
    // `WHERE a = 1 b = 2`
    if (block.clause == "CONDITION" && prev != null && (prev.isLiteral || prev.isName) && next?.isComparison == true) {
      out += SqlSyntaxProblem(t.start, t.end, PhantomDbBundle.message("sql.syntax.and.or.expected"), SqlSyntaxFix(t.start, t.start, "AND ", PhantomDbBundle.message("sql.syntax.fix.add", "AND")))
      return
    }
    // `SELECT a b c FROM t`: one name can be an alias, not two
    val before = tokens.getOrNull(index - 2)
    if (block.clause == "SELECT" && prev?.type == SqlTokens.IDENTIFIER && before?.type == SqlTokens.IDENTIFIER && tokens.getOrNull(index - 3)?.type != SqlTokens.DOT) {
      out += SqlSyntaxProblem(t.start, t.end, PhantomDbBundle.message("sql.syntax.comma.expected"), SqlSyntaxFix(prev.end, prev.end, ",", PhantomDbBundle.message("sql.syntax.fix.add", ",")))
    }
  }

  /** The keyword [t] is a misspelling of, when it stands where that keyword must and cannot be the name it looks like. */
  private fun typo(t: Token, prev: Token?, next: Token?, block: Block, index: Int, tokens: List<Token>): String? {
    if (index == 0 && block.open == null && next?.text != ":") return near(t.word, STATEMENT_STARTS)
    if (next?.isKeyword("BY") == true) return near(t.word, BY_CLAUSES)
    if (!block.isQuery || next == null || !(next.isName || next.isLiteral)) return null
    return when {
      // an alias named like FROM is possible only when the real FROM follows
      !block.sawFrom && block.clause == "SELECT" && prev != null && (prev.isName || prev.isLiteral || prev.type == SqlTokens.RPAREN || prev.text == "*") &&
      tokens.drop(index).none { it.isKeyword("FROM") } -> near(t.word, FROM_WORD)
      block.sawFrom && !block.sawWhere && prev?.isName == true -> near(t.word, AFTER_TABLE)
      else -> null
    }
  }

  private fun near(word: String, candidates: List<String>): String? =
    candidates.firstOrNull { word != it && distance(word, it) <= (if (it.length >= 6) 2 else 1) && word.length >= 3 }

  /** Edits between two words, a swap of neighbours counting as one. */
  internal fun distance(a: String, b: String): Int {
    val d = Array(a.length + 1) { i -> IntArray(b.length + 1) { j -> if (i == 0) j else if (j == 0) i else 0 } }
    for (i in 1..a.length) for (j in 1..b.length) {
      val cost = if (a[i - 1] == b[j - 1]) 0 else 1
      d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
      if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
    }
    return d[a.length][b.length]
  }

  private val COMPARISONS = setOf("=", "<", ">", "<=", ">=", "<>", "!=")
  private val AFTER_LIST = arrayOf("FROM", "WHERE", "GROUP", "HAVING", "ORDER", "LIMIT", "UNION")
  private val CLAUSES = arrayOf("WHERE", "GROUP", "HAVING", "ORDER", "LIMIT", "FROM", "JOIN", "UNION", "ON")
  internal val BEFORE_SELECT = arrayOf("UNION", "ALL", "DISTINCT", "EXCEPT", "INTERSECT", "MINUS")
  private val RANK = mapOf("FROM" to 1, "WHERE" to 2, "GROUP" to 3, "HAVING" to 4, "ORDER" to 5, "LIMIT" to 6)
  private val NAMES = mapOf(1 to "FROM", 2 to "WHERE", 3 to "GROUP BY", 4 to "HAVING", 5 to "ORDER BY", 6 to "LIMIT")
  private val STATEMENT_STARTS = listOf("SELECT", "INSERT", "UPDATE", "DELETE", "CREATE", "ALTER", "DROP")
  private val BY_CLAUSES = listOf("GROUP", "ORDER")
  private val FROM_WORD = listOf("FROM")
  private val AFTER_TABLE = listOf("WHERE", "JOIN")
}

/**
 * SQL that another plugin injects as whole statements (MyBatis mapper statements), so its syntax errors are real.
 * Other injected SQL may be half a statement and gets no syntax errors.
 */
interface SqlWholeInjection {
  /** [file] is SQL injected by this plugin, and reads as whole statements. */
  fun isWhole(file: PsiFile): Boolean

  companion object {
    val EP_NAME: ExtensionPointName<SqlWholeInjection> = ExtensionPointName.create("dev.phantom.db.wholeInjection")

    fun isWhole(file: PsiFile): Boolean = EP_NAME.extensionList.any { it.isWhole(file) }
  }
}

/**
 * Shows [SqlSyntaxCheck]'s problems as errors while typing. Not in SQL injected into code, which may be half a
 * statement, unless a [SqlWholeInjection] says it is whole.
 */
class SqlSyntaxAnnotator : Annotator, DumbAware {
  override fun annotate(element: PsiElement, holder: AnnotationHolder) {
    if (element !is SqlStatement) return
    if (InjectedLanguageManager.getInstance(element.project).isInjectedFragment(element.containingFile) &&
        !SqlWholeInjection.isWhole(element.containingFile)) return
    if (dev.phantom.db.core.ui.DbFilterContext.of(element.containingFile) != null) return
    val node = element.node
    if (node.firstChildNode?.elementType == SqlTokens.DELIMITER_COMMAND) return
    val base = element.textRange.startOffset
    missingSemicolon(element, holder)
    // a `DELIMITER` delimiter is no SQL: `END$$` alone would read as the name `END$$`
    val last = node.lastChildNode
    val text = if (last?.elementType == SqlTokens.DELIMITER) element.text.substring(0, last.startOffsetInParent) else element.text
    for (problem in SqlSyntaxCheck.check(text)) {
      var builder = holder.newAnnotation(HighlightSeverity.ERROR, problem.message).range(TextRange(base + problem.start, base + problem.end))
      problem.fix?.let { builder = builder.withFix(Fix(base, it)) }
      builder.create()
    }
  }

  /**
   * The parser ends a statement where its `;` is missing (`SqlParserContext.missingSemicolonAt`), so the next one
   * carries the error, with the fix after the last token of the one before.
   */
  private fun missingSemicolon(element: SqlStatement, holder: AnnotationHolder) {
    val first = element.node.firstChildNode ?: return
    var previous = element.node.treePrev
    while (previous != null && (previous.elementType == TokenType.WHITE_SPACE || previous.elementType in SqlTokens.COMMENTS)) previous = previous.treePrev
    if (previous == null || previous.elementType != SqlElements.STATEMENT) return
    val end = previous.lastChildNode ?: return
    if (end.elementType == SqlTokens.SEMICOLON || end.elementType == SqlTokens.DELIMITER ||
        previous.firstChildNode?.elementType == SqlTokens.DELIMITER_COMMAND) return
    val at = previous.textRange.endOffset
    val fix = SqlSyntaxFix(at, at, ";", PhantomDbBundle.message("sql.syntax.fix.add", ";"))
    val leaf = com.intellij.psi.impl.source.tree.TreeUtil.findFirstLeaf(first) ?: first
    holder.newAnnotation(HighlightSeverity.ERROR, PhantomDbBundle.message("sql.syntax.missing.semicolon")).range(leaf.textRange)
      .withFix(Fix(0, fix)).create()
  }

  private class Fix(private val base: Int, private val fix: SqlSyntaxFix) : IntentionAction {
    override fun getText(): String = fix.label
    override fun getFamilyName(): String = PhantomDbBundle.message("sql.syntax.fix.family")
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = editor != null
    override fun startInWriteAction(): Boolean = true
    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
      editor?.document?.replaceString(base + fix.start, base + fix.end, fix.text)
    }
  }
}

/** Syntax mistakes in SQL [text], for other plugins that check SQL they build themselves (MyBatis mapper statements). */
object SqlSyntaxProblems {
  class Problem(val range: TextRange, val message: String)

  fun of(text: CharSequence): List<Problem> = SqlSyntaxCheck.check(text).map { Problem(TextRange(it.start, it.end), it.message) }
}
