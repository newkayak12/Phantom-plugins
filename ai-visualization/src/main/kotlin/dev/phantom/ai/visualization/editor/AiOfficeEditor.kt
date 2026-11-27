// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import dev.phantom.ai.api.AiAgent
import dev.phantom.ai.api.PhantomAiApi
import dev.phantom.ai.visualization.AiVisualizationBundle
import dev.phantom.ai.visualization.settings.VizSettings
import dev.phantom.ai.visualization.settings.VizThemeListener
import dev.phantom.ai.visualization.ui.OfficeActions
import dev.phantom.ai.visualization.ui.OfficePanel
import java.awt.event.HierarchyEvent
import java.beans.PropertyChangeListener
import javax.swing.JComponent

/**
 * The AI Visualization editor tab. Its data comes from the shared [OfficeLoader], which reads [PhantomAiApi] and builds the model on
 * a pooled thread; this tab paints it on the EDT. A hidden tab does not cause loads and asks for one when it is shown again.
 */
class AiOfficeEditor(
  private val project: Project,
  private val file: VirtualFile,
  private val loader: OfficeLoader = OfficeLoader.getInstance(),
  theme: dev.phantom.ai.visualization.theme.Theme = VizSettings.getInstance().theme,
) : UserDataHolderBase(), FileEditor {
  private val api: () -> PhantomAiApi = loader.api
  private val panel = OfficePanel(theme, object : OfficeActions {
    override fun focus(tabId: String) {
      api().focusSession(project, tabId)
    }

    override fun start(worktreePath: String, agent: AiAgent) {
      api().startSession(project, worktreePath, agent)
    }
  })
  @Volatile private var disposed = false

  init {
    // Settings | Tools | Phantom AI Visualization: a new theme re-renders this tab
    ApplicationManager.getApplication().messageBus.connect(this).subscribe(VizSettings.TOPIC, VizThemeListener { t ->
      ApplicationManager.getApplication().invokeLater({ if (!disposed) panel.setTheme(t) }, ModalityState.any())
    })
    // back on screen: show current data (the timer restarts with the component, see Animation)
    panel.addHierarchyListener { e ->
      if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && panel.isShowing) loader.request()
    }
    loader.register(this, { panel.isShowing }) { m -> if (!disposed) panel.setModel(m, keyOf(project)) }
  }

  fun panel(): OfficePanel = panel

  override fun getComponent(): JComponent = panel
  override fun getPreferredFocusedComponent(): JComponent = panel.canvas
  override fun getName(): String = AiVisualizationBundle.message("editor.name")
  override fun getFile(): VirtualFile = file
  override fun setState(state: FileEditorState) = Unit
  override fun isModified(): Boolean = false
  override fun isValid(): Boolean = true
  override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
  override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
  override fun getCurrentLocation(): FileEditorLocation? = null

  override fun dispose() {
    disposed = true
    panel.dispose()
  }

  companion object {
    const val DEBOUNCE_MS = OfficeLoader.DEBOUNCE_MS

    /** Identifies a project in the model: its base path, else its name. */
    fun keyOf(p: Project): String = OfficeLoader.keyOf(p)
  }
}
