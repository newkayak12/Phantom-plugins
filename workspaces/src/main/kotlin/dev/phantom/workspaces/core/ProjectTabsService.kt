// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.workspaces.core

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.options.advanced.AdvancedSettingsChangeListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener
import com.intellij.openapi.project.ProjectNameListener
import com.intellij.openapi.wm.IdeFrame
import com.intellij.util.SystemProperties
import com.intellij.util.concurrency.annotations.RequiresEdt
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.HierarchyEvent
import javax.swing.JFrame

/**
 * The open project windows: their tab strips and the [WindowStack] that keeps the windows on top of each other.
 * A project window joins when its strip is added to the frame and leaves when the strip is removed. EDT only.
 *
 * The platform shows a new project frame at its own saved bounds and adds the strip only after that, once the project
 * is set. So a project frame about to be shown is put where the stack is when it gets its native window, right before
 * it is shown ([placeNewFrame]); it joins later, with its strip.
 */
@Service(Service.Level.APP)
class ProjectTabsService : Disposable {
  private val stack = WindowStack()
  private val strips = ArrayList<ProjectTabStrip>()
  private val openOrder = ArrayList<Project>()

  // Window.show() makes the frame displayable (addNotify) and only then shows it; the event comes in between
  private val newFrameListener = AWTEventListener { e ->
    if (e is HierarchyEvent && e.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() != 0L) placeNewFrame(e.component)
  }

  init {
    Toolkit.getDefaultToolkit().addAWTEventListener(newFrameListener, AWTEvent.HIERARCHY_EVENT_MASK)
  }

  override fun dispose() {
    Toolkit.getDefaultToolkit().removeAWTEventListener(newFrameListener)
  }

  val isEnabled: Boolean get() = AdvancedSettings.getBoolean(ProjectTabs.SETTING_ID)

  private fun placeNewFrame(component: Component) {
    // a project frame, not a detached editor window; not shown yet
    if (component !is JFrame || component !is IdeFrame || component is IdeFrame.Child) return
    if (!component.isDisplayable || component.isVisible || !ApplicationManager.getApplication().isDispatchThread) return
    if (isEnabled) stack.place(FrameWindow(component, null))
  }

  @RequiresEdt
  internal fun attach(strip: ProjectTabStrip) {
    if (strips.any { it === strip }) return
    strips += strip
    if (openOrder.none { it === strip.project }) openOrder += strip.project
    strip.window?.let { stack.add(it, align = isEnabled) }
    refresh()
  }

  @RequiresEdt
  internal fun detach(strip: ProjectTabStrip) {
    if (!strips.removeIf { it === strip }) return
    strip.window?.let(stack::remove)
    refresh()
  }

  @RequiresEdt
  internal fun windowActivated(window: StackedWindow) {
    if (isEnabled) stack.activated(window)
  }

  /** Records a move of [window] when it happens; true when it leads and [flushMoves] should follow (debounced). */
  @RequiresEdt
  internal fun windowMoved(window: StackedWindow): Boolean = isEnabled && stack.moved(window)

  /** Pushes the pending move; true when it is still pending (the window in front may be entering full screen). */
  @RequiresEdt
  internal fun flushMoves(): Boolean {
    if (!isEnabled) return false
    stack.flush()
    return stack.hasPendingMove
  }

  @RequiresEdt
  internal fun settingChanged() {
    if (isEnabled) {
      val windows = strips.mapNotNull { it.window }
      val leader = windows.firstOrNull { it.isActive } ?: windows.firstOrNull { it === stack.front } ?: windows.firstOrNull()
      leader?.let(stack::lead)
    }
    refresh()
  }

  /** Brings the window of [project] to front, in place. False when the project has no window here. */
  @RequiresEdt
  fun switchTo(project: Project): Boolean {
    val window = strips.firstOrNull { it.project === project }?.window ?: return false
    if (isEnabled) stack.switchTo(window) else window.activate()
    return true
  }

  /** Closes [project] the way File | Close Project does (the welcome screen comes back after the last one). */
  @RequiresEdt
  fun close(project: Project) {
    val action = ActionManager.getInstance().getAction(ProjectTabs.CLOSE_PROJECT_ACTION_ID) ?: return
    val context = SimpleDataContext.getProjectContext(project)
    val event = AnActionEvent.createEvent(action, context, null, ActionPlaces.UNKNOWN, ActionUiKind.NONE, null)
    ActionUtil.updateAction(action, event)
    if (event.presentation.isEnabled) ActionUtil.performAction(action, event)
  }

  internal fun projectClosed(project: Project) {
    refreshLater { openOrder.removeIf { it === project } }
  }

  internal fun refreshLater(before: () -> Unit = {}) {
    ApplicationManager.getApplication().invokeLater({ before(); refresh() }, ModalityState.any())
  }

  @RequiresEdt
  private fun refresh() {
    val open = openOrder
      .filter { project -> !project.isDisposed && strips.any { it.project === project } }
      .map { OpenProject(it, it.name, it.presentableUrl) }
    val visible = ProjectTabs.isStripVisible(isEnabled, open.size)
    val home = SystemProperties.getUserHome()
    for (strip in strips) {
      strip.display(ProjectTabs.tabs(open, strip.project, home), visible)
    }
  }

  companion object {
    fun getInstance(): ProjectTabsService = service()
  }
}

internal class ProjectTabsCloseListener : ProjectCloseListener {
  override fun projectClosed(project: Project) {
    serviceIfCreated<ProjectTabsService>()?.projectClosed(project)
  }
}

internal class ProjectTabsRenameListener : ProjectNameListener {
  override fun nameChanged(newName: String?) {
    serviceIfCreated<ProjectTabsService>()?.refreshLater()
  }
}

internal class ProjectTabsSettingListener : AdvancedSettingsChangeListener {
  override fun advancedSettingChanged(id: String, oldValue: Any, newValue: Any) {
    if (id != ProjectTabs.SETTING_ID) return
    val service = serviceIfCreated<ProjectTabsService>() ?: return
    ApplicationManager.getApplication().invokeLater({ service.settingChanged() }, ModalityState.any())
  }
}
