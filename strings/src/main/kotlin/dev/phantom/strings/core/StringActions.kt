// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.strings.core

import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import org.jetbrains.annotations.PropertyKey

enum class Kind {
  /** Without a selection, works on the identifier at the caret. */
  WORD,
  /** Needs a selection. */
  TEXT,
  LINES,
}

enum class Transform(@PropertyKey(resourceBundle = "messages.PhantomStringsBundle") val key: String, val kind: Kind, val apply: (String) -> String) {
  CYCLE("transform.cycle", Kind.WORD, StringTransforms::cycleCase),
  CAMEL("transform.camel", Kind.WORD, StringTransforms::camel),
  PASCAL("transform.pascal", Kind.WORD, StringTransforms::pascal),
  SNAKE("transform.snake", Kind.WORD, StringTransforms::snake),
  SCREAMING_SNAKE("transform.screaming", Kind.WORD, StringTransforms::screamingSnake),
  KEBAB("transform.kebab", Kind.WORD, StringTransforms::kebab),
  DOT("transform.dot", Kind.WORD, StringTransforms::dot),
  SPACED("transform.spaced", Kind.WORD, StringTransforms::spaced),
  UPPER("transform.upper", Kind.WORD, String::uppercase),
  LOWER("transform.lower", Kind.WORD, String::lowercase),
  ESCAPE_JAVA("transform.escape.java", Kind.TEXT, StringTransforms::escapeJava),
  UNESCAPE_JAVA("transform.unescape.java", Kind.TEXT, StringTransforms::unescapeJava),
  ESCAPE_HTML("transform.escape.html", Kind.TEXT, StringTransforms::escapeHtml),
  UNESCAPE_HTML("transform.unescape.html", Kind.TEXT, StringTransforms::unescapeHtml),
  ENCODE_URL("transform.encode.url", Kind.TEXT, StringTransforms::encodeUrl),
  DECODE_URL("transform.decode.url", Kind.TEXT, StringTransforms::decodeUrl),
  ENCODE_BASE64("transform.encode.base64", Kind.TEXT, StringTransforms::encodeBase64),
  DECODE_BASE64("transform.decode.base64", Kind.TEXT, StringTransforms::decodeBase64),
  SORT("transform.sort", Kind.LINES, StringTransforms::sortLines),
  SORT_DESC("transform.sort.desc", Kind.LINES, StringTransforms::sortLinesDescending),
  REVERSE("transform.reverse", Kind.LINES, StringTransforms::reverseLines),
  SHUFFLE("transform.shuffle", Kind.LINES, { StringTransforms.shuffleLines(it) }),
  DISTINCT("transform.distinct", Kind.LINES, StringTransforms::removeDuplicateLines),
  REMOVE_BLANK("transform.remove.blank", Kind.LINES, StringTransforms::removeBlankLines),
  TRIM("transform.trim", Kind.LINES, StringTransforms::trimLines),
}

class TransformAction(private val transform: Transform) : DumbAwareAction(PhantomStringsBundle.message(transform.key)) {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val editor = e.getData(CommonDataKeys.EDITOR)
    e.presentation.isEnabled = editor != null && editor.document.isWritable &&
      (transform.kind == Kind.WORD || editor.selectionModel.hasSelection(true))
  }

  override fun actionPerformed(e: AnActionEvent) {
    val editor = e.getData(CommonDataKeys.EDITOR) ?: return
    var failed = false
    WriteCommandAction.runWriteCommandAction(e.project, templateText, null, {
      // last caret first, so a replacement does not move the ranges of the carets before it
      var lastStart = -1
      for (caret in editor.caretModel.allCarets.asReversed()) {
        val start = apply(editor, caret, lastStart)
        if (start == null) failed = true else lastStart = start
      }
    })
    if (failed) HintManager.getInstance().showErrorHint(editor, PhantomStringsBundle.message("transform.failed"))
  }

  /**
   * Converts the caret's range unless it starts at [doneStart], the range converted for the caret after it (two carets in
   * one identifier). Returns the range's start, or null if the transform rejected the text.
   */
  private fun apply(editor: Editor, caret: Caret, doneStart: Int): Int? {
    val text = editor.document.charsSequence
    val selected = caret.hasSelection()
    val (start, end) = when {
      selected -> caret.selectionStart to caret.selectionEnd
      transform.kind == Kind.WORD -> wordAt(text, caret.offset) ?: return doneStart
      else -> return doneStart
    }
    if (start == doneStart) return start
    val result = try {
      transform.apply(text.subSequence(start, end).toString())
    }
    catch (_: IllegalArgumentException) {
      return null
    }
    editor.document.replaceString(start, end, result)
    if (selected) caret.setSelection(start, start + result.length)
    else caret.moveToOffset(start + result.length)
    return start
  }

  private fun wordAt(text: CharSequence, offset: Int): Pair<Int, Int>? {
    fun part(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'
    var start = offset
    while (start > 0 && part(text[start - 1])) start--
    var end = offset
    while (end < text.length && part(text[end])) end++
    return if (start < end) start to end else null
  }
}

/** Every transform, grouped by kind; its id is Phantom.Strings. */
class StringsActionGroup : ActionGroup(), DumbAware {
  private val children: Array<AnAction> by lazy {
    val actions = ArrayList<AnAction>()
    for ((kind, transforms) in Transform.entries.groupBy { it.kind }) {
      if (kind != Kind.WORD) actions += Separator.getInstance()
      transforms.mapTo(actions) { TransformAction(it) }
    }
    actions.toTypedArray()
  }

  override fun getActionUpdateThread() = ActionUpdateThread.BGT

  override fun getChildren(e: AnActionEvent?): Array<AnAction> = children
}

/** Alt+M: the transforms in a popup with speed search. */
class StringsPopupAction : DumbAwareAction() {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabled = e.getData(CommonDataKeys.EDITOR) != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val group = ActionManager.getInstance().getAction("Phantom.Strings") as? ActionGroup ?: return
    JBPopupFactory.getInstance()
      .createActionGroupPopup(PhantomStringsBundle.message("popup.title"), group, e.dataContext,
                              JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true)
      .showInBestPositionFor(e.dataContext)
  }
}
