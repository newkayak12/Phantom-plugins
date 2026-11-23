// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.logs

import com.intellij.execution.filters.ConsoleFilterProvider
import com.intellij.execution.filters.ConsoleInputFilterProvider
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.execution.filters.InputFilter
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.impl.EditorComponentImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Pair
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.UIUtil
import java.awt.MouseInfo

/**
 * Rewrites what a process prints before the console shows it: a JSON log line becomes `time LEVEL [thread] logger : message`
 * with its stack trace below, and a plain text WARN, ERROR or DEBUG line takes that level's console color. Only whole
 * lines in the process's own colors are touched; a line the process colored itself (ANSI) keeps its colors.
 */
class LogInputFilterProvider : ConsoleInputFilterProvider {
  override fun getDefaultFilters(project: Project): Array<InputFilter> = arrayOf(LogInputFilter)
}

object LogInputFilter : InputFilter {
  override fun applyFilter(text: String, contentType: ConsoleViewContentType): List<Pair<String, ConsoleViewContentType>>? {
    if (contentType != ConsoleViewContentType.NORMAL_OUTPUT && contentType != ConsoleViewContentType.ERROR_OUTPUT) return null
    var changed = false
    val parts = ArrayList<Pair<String, ConsoleViewContentType>>()
    // Lines of one type in a row are collected in a builder: appending each to the last part's String copied that part
    // again for every line, quadratic in the lines of a chunk, even when nothing was changed and the parts were dropped.
    val run = StringBuilder()
    var runType: ConsoleViewContentType? = null
    var start = 0
    while (start < text.length) {
      val end = text.indexOf('\n', start).let { if (it < 0) text.length else it + 1 }
      val line = text.substring(start, end)
      val whole = line.endsWith('\n')
      val json = if (whole) LogLines.json(line) else null
      val (shown, level) = when {
        json != null -> json.text + "\n" to json.level
        whole -> line to LogLines.level(line)
        else -> line to null
      }
      val type = typeOf(level) ?: contentType
      if (json != null || type != contentType) changed = true
      if (type != runType) {
        runType?.let { parts.add(Pair(run.toString(), it)) }
        run.setLength(0)
        runType = type
      }
      run.append(shown)
      start = end
    }
    if (!changed) return null
    runType?.let { parts.add(Pair(run.toString(), it)) }
    return parts
  }

  private fun typeOf(level: LogLevel?): ConsoleViewContentType? = when (level) {
    LogLevel.ERROR -> ConsoleViewContentType.LOG_ERROR_OUTPUT
    LogLevel.WARN -> ConsoleViewContentType.LOG_WARNING_OUTPUT
    LogLevel.DEBUG, LogLevel.TRACE -> ConsoleViewContentType.LOG_DEBUG_OUTPUT
    else -> null
  }
}

/** Trace ids in console lines as links: a click lists every line of the console with the same id. */
class TraceIdFilterProvider : ConsoleFilterProvider {
  override fun getDefaultFilters(project: Project): Array<Filter> = arrayOf(TraceIdFilter)
}

object TraceIdFilter : Filter {
  override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
    val ranges = LogLines.traceIds(line)
    if (ranges.isEmpty()) return null
    val lineStart = entireLength - line.length
    return Filter.Result(ranges.map { range ->
      Filter.ResultItem(lineStart + range.first, lineStart + range.last + 1, TraceIdLink(line.substring(range)))
    })
  }
}

class TraceIdLink(val traceId: String) : HyperlinkInfo {
  override fun navigate(project: Project) {
    val focused = IdeFocusManager.getInstance(project).focusOwner ?: return
    val editor = UIUtil.getParentOfType(EditorComponentImpl::class.java, focused)?.editor ?: return
    val lines = TraceLines.of(editor.document.charsSequence, traceId)
    if (lines.isEmpty()) return
    JBPopupFactory.getInstance().createPopupChooserBuilder(lines)
      .setTitle(PhantomLogsBundle.message("trace.popup.title", traceId, lines.size))
      .setRenderer(com.intellij.ui.SimpleListCellRenderer.create("") { "${it.number + 1}: ${it.text.take(240)}" })
      .setNamerForFiltering { it.text }
      .setItemChosenCallback { show(editor, it) }
      .createPopup()
      .show(RelativePoint.fromScreen(MouseInfo.getPointerInfo()?.location ?: return))
  }

  private fun show(editor: Editor, line: TraceLine) {
    val offset = editor.document.getLineStartOffset(line.number)
    editor.caretModel.moveToOffset(offset)
    editor.selectionModel.setSelection(offset, editor.document.getLineEndOffset(line.number))
    editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
  }
}

class TraceLine(val number: Int, val text: String)

object TraceLines {
  /** The lines of a console text that contain the trace id. */
  fun of(text: CharSequence, traceId: String): List<TraceLine> =
    text.lineSequence().withIndex().filter { (_, line) -> line.contains(traceId) }.map { TraceLine(it.index, it.value) }.toList()
}
