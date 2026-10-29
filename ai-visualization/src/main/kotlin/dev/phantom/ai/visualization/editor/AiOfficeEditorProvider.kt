// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/** Opens [AiOfficeFile] (and nothing else) as the AI Office editor. */
class AiOfficeEditorProvider : FileEditorProvider, DumbAware {
  // with an AI Integration older than 0.3.7 (no public API, no AiSubagent or no activity) a tab left open is not restored instead of failing
  override fun accept(project: Project, file: VirtualFile): Boolean = file is AiOfficeFile && dev.phantom.ai.visualization.actions.ApiCheck.available

  override fun createEditor(project: Project, file: VirtualFile): FileEditor = AiOfficeEditor(project, file)

  override fun getEditorTypeId(): String = "dev.phantom.ai.visualization.office"

  override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
