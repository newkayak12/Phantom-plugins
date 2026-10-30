// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.Alarm
import dev.phantom.ai.api.AiAgent
import dev.phantom.ai.api.PhantomAiApi
import dev.phantom.ai.visualization.model.OfficeModel
import dev.phantom.ai.visualization.model.ProjectData
import dev.phantom.ai.visualization.settings.VizSettings
import dev.phantom.ai.visualization.settings.VizThemeListener
import dev.phantom.ai.visualization.ui.OfficeActions
import dev.phantom.ai.visualization.ui.OfficePanel
import java.awt.event.HierarchyEvent
import java.beans.PropertyChangeListener
import javax.swing.JComponent

/**
 * The AI Visualization editor tab. Reads everything through [PhantomAiApi]: a change listener (disposed with the editor) debounces,
 * worktrees and sessions of the open projects are read on a pooled thread, the result is painted on the EDT.
 */
class AiOfficeEditor(
  private val project: Project,
  private val file: VirtualFile,
  private val api: () -> PhantomAiApi = { PhantomAiApi.getInstance() },
  theme: dev.phantom.ai.visualization.theme.Theme = VizSettings.getInstance().theme,
) : UserDataHolderBase(), FileEditor {
  private val panel = OfficePanel(theme, object : OfficeActions {
    override fun focus(tabId: String) {
      api().focusSession(project, tabId)
    }

    override fun start(worktreePath: String, agent: AiAgent) {
      api().startSession(project, worktreePath, agent)
    }
  })
  private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

  @Volatile private var disposed = false

  init {
    api().addChangeListener(this) { request() }
    // Settings | Tools | Phantom AI Visualization: a new theme re-renders this tab
    ApplicationManager.getApplication().messageBus.connect(this).subscribe(VizSettings.TOPIC, VizThemeListener { t ->
      ApplicationManager.getApplication().invokeLater({ if (!disposed) panel.setTheme(t) }, ModalityState.any())
    })
    // back on screen: show current data (the timer restarts with the component, see Animation)
    panel.addHierarchyListener { e ->
      if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && panel.isShowing) request()
    }
    request()
  }

  fun panel(): OfficePanel = panel

  /** Reads the data after a short delay; a newer request replaces an older one. */
  fun request() {
    if (disposed) return
    alarm.cancelAllRequests()
    alarm.addRequest({ refreshNow() }, DEBOUNCE_MS)
  }

  private fun refreshNow() {
    if (disposed) return
    val data = load()
    ApplicationManager.getApplication().invokeLater({
      if (!disposed) panel.setModel(OfficeModel.build(data), keyOf(project))
    }, ModalityState.any())
  }

  private fun load(): List<ProjectData> {
    val a = api()
    return ProjectManager.getInstance().openProjects.filter { !it.isDefault && !it.isDisposed }.map { p ->
      try {
        ProjectData(keyOf(p), p.name, a.worktrees(p), a.sessions(p))
      } catch (e: ProcessCanceledException) {
        throw e
      } catch (e: Exception) {
        LOG.warn("AI Visualization: could not read ${p.name}", e)
        ProjectData(keyOf(p), p.name, emptyList(), emptyList())
      }
    }
  }

  override fun getComponent(): JComponent = panel
  override fun getPreferredFocusedComponent(): JComponent = panel.canvas
  override fun getName(): String = "AI Visualization"
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
    private val LOG = logger<AiOfficeEditor>()
    const val DEBOUNCE_MS = 150

    /** Identifies a project in the model: its base path, else its name. */
    fun keyOf(p: Project): String = p.basePath ?: p.name
  }
}
