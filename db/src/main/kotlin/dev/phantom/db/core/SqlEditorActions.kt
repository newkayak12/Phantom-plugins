// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core

import com.intellij.codeInsight.editorActions.ExtendWordSelectionHandler
import com.intellij.codeInsight.editorActions.smartEnter.SmartEnterProcessor
import com.intellij.lang.surroundWith.SurroundDescriptor
import com.intellij.lang.surroundWith.Surrounder
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.util.PsiTreeUtil

/**
 * Extend Selection inside a query: the list item (`a.x + 1` between commas), the clause body and the clause
 * (`WHERE ...` up to the next clause), and the inside and outside of parentheses. The statement and subqueries
 * themselves are PSI elements the platform selects on its own.
 */
class SqlExtendSelectionHandler : ExtendWordSelectionHandler {
  override fun canSelect(e: PsiElement): Boolean = e is SqlScope

  override fun select(e: PsiElement, editorText: CharSequence, cursorOffset: Int, editor: Editor): List<TextRange> {
    val items = SqlQueries.items(e)
    val at = items.indexOfFirst { cursorOffset >= it.element.textRange.startOffset && cursorOffset < it.element.textRange.endOffset }
      .takeIf { it >= 0 } ?: items.indexOfLast { it.element.textRange.endOffset == cursorOffset }.takeIf { it >= 0 } ?: return emptyList()
    fun range(from: Int, to: Int) = TextRange(items[from].element.textRange.startOffset, items[to].element.textRange.endOffset)
    val result = ArrayList<TextRange>()
    var depth = items[at].depth
    while (depth >= 0) {
      var lo = at
      while (lo > 0 && items[lo - 1].depth >= depth) lo--
      var hi = at
      while (hi < items.size - 1 && items[hi + 1].depth >= depth) hi++
      fun starts(j: Int) = items[j].depth == depth && items[j].keyword in CLAUSES
      val clause = (at downTo lo).firstOrNull { starts(it) }
      val clauseEnd = ((at + 1)..hi).firstOrNull { starts(it) }?.let { it - 1 } ?: hi
      var bodyStart = lo
      if (clause != null) {
        bodyStart = clause + 1
        if (items.getOrNull(bodyStart)?.isKeyword("BY") == true) bodyStart++
        if (clauseEnd > clause) result += range(clause, clauseEnd)
        if (bodyStart in (clause + 1)..clauseEnd) result += range(bodyStart, clauseEnd)
      }
      fun comma(j: Int) = items[j].depth == depth && items[j].type == SqlTokens.COMMA
      if (at >= bodyStart && !comma(at)) {
        var from = at
        while (from > bodyStart && !comma(from - 1)) from--
        var to = at
        while (to < clauseEnd && !comma(to + 1)) to++
        result += range(from, to)
      }
      if (depth > 0 && lo > 0 && hi < items.size - 1) {
        result += range(lo, hi)
        result += range(lo - 1, hi + 1)
      }
      depth--
    }
    return result.filter { it.containsOffset(cursorOffset) }
  }

  private companion object {
    val CLAUSES = setOf("SELECT", "FROM", "WHERE", "GROUP", "HAVING", "ORDER", "LIMIT", "OFFSET", "SET", "VALUES", "RETURNING", "WINDOW",
                        "UNION", "INTERSECT", "EXCEPT", "MINUS")
  }
}

/** Surround With (Ctrl+Alt+T / Cmd+Opt+T) for SQL: parentheses, COALESCE, CASE and NOT. */
class SqlSurroundDescriptor : SurroundDescriptor {
  override fun getElementsToSurround(file: PsiFile, startOffset: Int, endOffset: Int): Array<PsiElement> {
    if (file !is SqlFile) return PsiElement.EMPTY_ARRAY
    val text = file.text
    var start = startOffset
    var end = endOffset
    while (start < end && text[start].isWhitespace()) start++
    while (end > start && text[end - 1].isWhitespace()) end--
    if (start >= end) return PsiElement.EMPTY_ARRAY
    val first = file.findElementAt(start) ?: return PsiElement.EMPTY_ARRAY
    val last = file.findElementAt(end - 1) ?: return PsiElement.EMPTY_ARRAY
    if (first == last) return arrayOf(first)
    val parent = PsiTreeUtil.findCommonParent(first, last) ?: return PsiElement.EMPTY_ARRAY
    var from: PsiElement = first
    while (from.parent != parent) from = from.parent
    var to: PsiElement = last
    while (to.parent != parent) to = to.parent
    val result = ArrayList<PsiElement>()
    var e: PsiElement? = from
    while (e != null) {
      result += e
      if (e == to) break
      e = e.nextSibling
    }
    return result.toTypedArray()
  }

  override fun getSurrounders(): Array<Surrounder> = SURROUNDERS
  override fun isExclusive(): Boolean = false

  private companion object {
    val SURROUNDERS: Array<Surrounder> = arrayOf(
      SqlTextSurrounder("(expression)", "(", ")", 1),
      SqlTextSurrounder("COALESCE(expression, …)", "COALESCE(", ", )", 2),
      SqlTextSurrounder("CASE WHEN condition THEN … END", "CASE WHEN ", " THEN  END", 6),
      SqlTextSurrounder("NOT (condition)", "NOT (", ")", 1),
    )
  }
}

/** Wraps the selected text in [before] and [after]; the caret ends [caretAfterText] characters after the wrapped text. */
class SqlTextSurrounder(private val title: String, private val before: String, private val after: String, private val caretAfterText: Int) : Surrounder {
  override fun getTemplateDescription(): String = title
  override fun isApplicable(elements: Array<out PsiElement>): Boolean = elements.isNotEmpty()

  override fun surroundElements(project: Project, editor: Editor, elements: Array<out PsiElement>): TextRange {
    val start = elements.first().textRange.startOffset
    val end = elements.last().textRange.endOffset
    val document = editor.document
    val inner = document.getText(TextRange(start, end))
    document.replaceString(start, end, before + inner + after)
    val caret = start + before.length + inner.length + caretAfterText
    return TextRange(caret, caret)
  }
}

/**
 * Complete Current Statement (Ctrl+Shift+Enter / Cmd+Shift+Enter): closes an open string or quoted name and open
 * parentheses, adds the `;`, and starts a new line after the statement.
 */
class SqlSmartEnterProcessor : SmartEnterProcessor() {
  override fun process(project: Project, editor: Editor, psiFile: PsiFile): Boolean {
    val file = psiFile as? SqlFile ?: return false
    val statement = file.statementAt(editor.caretModel.offset) ?: return false
    val document = editor.document
    val start = statement.textRange.startOffset
    val text = statement.text
    val lexer = SqlLexer()
    lexer.start(text)
    val open = ArrayList<Char>()
    var lastEnd = 0
    var semicolon = false
    var closer = ""
    while (lexer.tokenType != null) {
      val type = lexer.tokenType
      val token = text.substring(lexer.tokenStart, lexer.tokenEnd)
      when (type) {
        SqlTokens.LPAREN -> open += ')'
        SqlTokens.RPAREN -> open.removeLastOrNull()
        SqlTokens.SEMICOLON -> semicolon = true
      }
      if (type != TokenType.WHITE_SPACE && type != SqlTokens.SEMICOLON) lastEnd = lexer.tokenEnd
      if (type == SqlTokens.STRING || type == SqlTokens.QUOTED_IDENTIFIER) closer = unclosed(token) ?: closer
      else if (type != TokenType.WHITE_SPACE) closer = ""
      lexer.advance()
    }
    val insert = closer + open.reversed().joinToString("") + if (semicolon) "" else ";"
    val at = start + lastEnd
    document.insertString(at, insert)
    var end = at + insert.length
    if (semicolon) end = start + text.indexOf(';', lastEnd).coerceAtLeast(lastEnd) + insert.length + 1
    // stay on an existing empty line after the statement, else open one
    val line = document.getLineNumber(end)
    if (document.getLineEndOffset(line) == end && line + 1 < document.lineCount && document.getText(
        TextRange(document.getLineStartOffset(line + 1), document.getLineEndOffset(line + 1))).isBlank()) {
      editor.caretModel.moveToOffset(document.getLineEndOffset(line + 1))
    }
    else {
      document.insertString(end, "\n")
      editor.caretModel.moveToOffset(end + 1)
    }
    return true
  }

  /** The quote that closes [token] when it is not closed. */
  private fun unclosed(token: String): String? {
    val first = token.firstOrNull() ?: return null
    if (first !in "'\"`[") return null
    val close = if (first == '[') ']' else first
    return if (token.length < 2 || token.last() != close) close.toString() else null
  }
}
