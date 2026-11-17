// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.board

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import dev.phantom.ai.core.PhantomAiBundle
import java.beans.PropertyChangeListener
import javax.swing.JComponent

/** The one "Board" file of a project; it is never on disk and cannot be edited. */
class BoardVirtualFile : LightVirtualFile("Board") {
  init {
    isWritable = false
  }

  override fun isWritable(): Boolean = false

  companion object {
    private val FILES = com.intellij.openapi.util.Key.create<BoardVirtualFile>("dev.phantom.ai.board.file")

    /** The project's single instance, so a second open selects the same tab. */
    fun of(project: Project): BoardVirtualFile =
      project.getUserData(FILES) ?: BoardVirtualFile().also { project.putUserData(FILES, it) }
  }
}

class BoardFileEditorProvider : FileEditorProvider, DumbAware {
  override fun accept(project: Project, file: VirtualFile): Boolean = file is BoardVirtualFile

  override fun createEditor(project: Project, file: VirtualFile): FileEditor = BoardFileEditor(project, file)

  override fun getEditorTypeId(): String = "dev.phantom.ai.board"

  override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/** Shows [WorkspaceBoardPanel] as an editor tab and disposes it with the editor. */
class BoardFileEditor(project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {
  private val panel = WorkspaceBoardPanel(project)

  init {
    Disposer.register(this, panel)
  }

  fun panel(): WorkspaceBoardPanel = panel

  override fun getComponent(): JComponent = panel
  override fun getPreferredFocusedComponent(): JComponent = panel
  override fun getName(): String = PhantomAiBundle.message("board.title")
  override fun getFile(): VirtualFile = file
  override fun setState(state: FileEditorState) = Unit
  override fun isModified(): Boolean = false
  override fun isValid(): Boolean = true
  override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
  override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
  override fun getCurrentLocation(): FileEditorLocation? = null
  override fun dispose() = Unit
}

class OpenBoardAction : AnAction(), DumbAware {
  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    FileEditorManager.getInstance(project).openFile(BoardVirtualFile.of(project), true)
  }
}
