// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import dev.phantom.ai.visualization.editor.AiOfficeFile

/** Tools | Open AI Office (also in Find Action): opens the "AI Office" tab of the project, or selects it when it is open. */
class OpenAiOfficeAction : AnAction(), DumbAware {
  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    if (!ApiCheck.available) {
      Messages.showWarningDialog(
        project,
        "Phantom AI Visualization needs Phantom AI Integration 0.3.1 or later. Update it in Settings | Plugins and restart.",
        "Open AI Office",
      )
      return
    }
    FileEditorManager.getInstance(project).openFile(AiOfficeFile.of(project), true)
  }
}
