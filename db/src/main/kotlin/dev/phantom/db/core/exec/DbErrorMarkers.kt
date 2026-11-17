// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.TokenType
import dev.phantom.db.core.SqlLexer
import org.jetbrains.annotations.TestOnly

/** Where in a statement a database says it went wrong: from PostgreSQL's position, H2's `[*]`, MySQL's `near '…'` or the name it quotes. */
internal object DbErrorPosition {
  private val POSTGRES = Regex("Position: (\\d+)")
  private val MYSQL_NEAR = Regex("near '(.*)' at line (\\d+)", RegexOption.DOT_MATCHES_ALL)
  private val NAMED = Regex("(?:Unknown column|Unknown table|Table) '([^']+)'|(?:Column|Table) \"([^\"]+)\" not found")

  /** The offset in [sql], or null when the message does not say. */
  fun find(sql: String, message: String): Int? {
    POSTGRES.find(message)?.let { m -> return (m.groupValues[1].toInt() - 1).takeIf { it in sql.indices } }
    val marker = message.indexOf("[*]")
    if (marker >= 0) return afterText(sql, message.substring(0, marker))
    MYSQL_NEAR.find(message)?.let { m -> return mysqlNear(sql, m.groupValues[1], m.groupValues[2].toInt()) }
    NAMED.find(message)?.let { m -> return wholeWord(sql, m.groupValues.drop(1).first { it.isNotEmpty() }.substringAfterLast('.')) }
    return null
  }

  /** The end of the longest tail of [before] that [sql] contains: the message quotes the statement up to the marker. */
  private fun afterText(sql: String, before: String): Int? {
    for (length in minOf(before.length, 60) downTo 4) {
      val tail = before.takeLast(length)
      val at = sql.indexOf(tail)
      if (at >= 0) return (at + tail.length).coerceAtMost(sql.length - 1)
    }
    return null
  }

  private fun mysqlNear(sql: String, snippet: String, line: Int): Int? {
    if (snippet.isEmpty()) return (sql.length - 1).takeIf { it >= 0 }
    var start = 0
    repeat(line - 1) { start = sql.indexOf('\n', start).let { if (it < 0) return null else it + 1 } }
    return sql.indexOf(snippet.take(40), start).takeIf { it >= 0 }
  }

  private fun wholeWord(sql: String, word: String): Int? = Regex("(?<![\\w`\"])${Regex.escape(word)}(?![\\w`\"])", RegexOption.IGNORE_CASE).find(sql)?.range?.first
    ?: sql.indexOf(word, ignoreCase = true).takeIf { it >= 0 }

  /** The token at [offset] of [sql] as a range of it; the next token when [offset] falls on blanks. */
  fun token(sql: String, offset: Int): IntRange {
    val lexer = SqlLexer()
    lexer.start(sql)
    while (lexer.tokenType != null) {
      if (lexer.tokenEnd > offset && lexer.tokenType != TokenType.WHITE_SPACE) return lexer.tokenStart until lexer.tokenEnd
      lexer.advance()
    }
    return offset until (offset + 1).coerceAtMost(sql.length)
  }
}

/** Marks the statement that failed, at the token the database names, in the editor it was run from; the next edit or run clears it. */
internal object DbErrorMarkers {
  private class Mark(val highlighter: RangeHighlighter, val scope: Disposable)
  private val KEY = Key.create<Mark>("phantom.db.errorMark")

  fun clear(project: Project, file: VirtualFile) = editors(project, file).forEach(::clear)

  private fun editors(project: Project, file: VirtualFile): List<Editor> =
    FileEditorManager.getInstance(project).getEditors(file).filterIsInstance<TextEditor>().map { it.editor }

  private fun clear(editor: Editor) {
    val mark = editor.getUserData(KEY) ?: return
    editor.putUserData(KEY, null)
    editor.markupModel.removeHighlighter(mark.highlighter)
    Disposer.dispose(mark.scope)
  }

  fun show(project: Project, file: VirtualFile, outcomes: List<DbOutcome>) {
    val failed = outcomes.filterIsInstance<DbOutcome.Failed>().firstOrNull() ?: return
    val editor = editors(project, file).firstOrNull() ?: return
    mark(editor, failed.sql, failed.message)
  }

  fun mark(editor: Editor, sql: String, message: String) {
    clear(editor)
    val text = editor.document.text
    val statement = nearest(text, sql, editor.caretModel.offset) ?: return
    val range = DbErrorPosition.find(sql, message)?.let { DbErrorPosition.token(sql, it) } ?: sql.indices
    if (range.isEmpty()) return
    val start = statement + range.first
    val end = statement + range.last + 1
    val highlighter = editor.markupModel.addRangeHighlighter(CodeInsightColors.ERRORS_ATTRIBUTES, start, end, HighlighterLayer.ERROR, HighlighterTargetArea.EXACT_RANGE)
    highlighter.errorStripeTooltip = message
    highlighter.setErrorStripeMarkColor(com.intellij.ui.JBColor.RED)
    val scope = Disposer.newDisposable("phantom.db.errorMark")
    EditorUtil.disposeWithEditor(editor, scope)
    editor.putUserData(KEY, Mark(highlighter, scope))
    editor.document.addDocumentListener(object : DocumentListener {
      override fun documentChanged(event: DocumentEvent) = clear(editor)
    }, scope)
    HintManager.getInstance().showErrorHint(editor, message.lineSequence().first(), start, end, HintManager.ABOVE, HintManager.HIDE_BY_ANY_KEY or HintManager.HIDE_BY_TEXT_CHANGE, 0)
  }

  /** The occurrence of [sql] closest to [caret]: the same statement can be in the file twice. */
  private fun nearest(text: String, sql: String, caret: Int): Int? =
    generateSequence(text.indexOf(sql)) { text.indexOf(sql, it + 1) }.takeWhile { it >= 0 }.minByOrNull { kotlin.math.abs(it - caret) }

  @TestOnly fun markedRange(editor: Editor): IntRange? = editor.getUserData(KEY)?.highlighter?.let { it.startOffset until it.endOffset }
}
