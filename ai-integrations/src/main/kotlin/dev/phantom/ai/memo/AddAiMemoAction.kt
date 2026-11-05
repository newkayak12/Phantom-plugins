// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.memo

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction

/** Opens the memo input for the selected lines, or for the caret line; only in files of the project's worktree. */
class AddAiMemoAction : DumbAwareAction() {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val editor = e.getData(CommonDataKeys.EDITOR)
    val project = e.project
    e.presentation.isEnabledAndVisible = editor != null && project != null &&
      MemoEditorBlocks.inWorktree(AiMemoStore.getInstance(project), FileDocumentManager.getInstance().getFile(editor.document))
  }

  override fun actionPerformed(e: AnActionEvent) {
    val editor = e.getData(CommonDataKeys.EDITOR) ?: return
    val doc = editor.document
    val selection = editor.selectionModel
    val (start, end) = if (selection.hasSelection()) {
      val first = doc.getLineNumber(selection.selectionStart)
      val last = doc.getLineNumber(selection.selectionEnd)
      // a selection that ends at the start of a line does not take that line
      first to if (last > first && doc.getLineStartOffset(last) == selection.selectionEnd) last - 1 else last
    } else {
      editor.caretModel.logicalPosition.line.let { it to it }
    }
    MemoEditorBlocks.attach(editor)?.openInput(start, end)
  }
}
