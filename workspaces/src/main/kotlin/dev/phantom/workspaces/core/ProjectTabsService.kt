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
import com.intellij.openapi.util.io.FileUtil
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
    stack.single = isEnabled
  }

  override fun dispose() {
    Toolkit.getDefaultToolkit().removeAWTEventListener(newFrameListener)
    // the plugin is unloaded: no window stays hidden
    stack.single = false
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
    val next = strip.window?.let(stack::remove)
    // the window in front closed: the one before it comes back in its place, unless the IDE is quitting
    if (next != null && !ApplicationManager.getApplication().isExitInProgress) {
      ApplicationManager.getApplication().invokeLater({
        if (strips.any { it.window === next }) stack.switchTo(next)
      }, ModalityState.any())
    }
    refresh()
  }

  /** Also when a hidden project window is shown by someone else than the stack: it comes to front in place. */
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
    stack.single = isEnabled
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
    if (isEnabled) {
      // before the window comes to front, so it shows with the layout already
      strips.firstOrNull { it.window === stack.front }?.project?.let { ToolWindowLayoutSync.copy(it, project) }
      stack.switchTo(window)
    }
    else {
      window.activate()
    }
    return true
  }

  /** Switches to the open project at [path] (its presentable URL or base path). False when none is open here. */
  @RequiresEdt
  fun switchTo(path: String): Boolean {
    val wanted = FileUtil.toSystemIndependentName(path).trimEnd('/')
    val project = openOrder.firstOrNull { p ->
      !p.isDisposed && listOfNotNull(p.presentableUrl, p.basePath).any { FileUtil.toSystemIndependentName(it).trimEnd('/') == wanted }
    } ?: return false
    return switchTo(project)
  }

  /** Switches to the project [offset] tabs away from [project], wrapping around. */
  @RequiresEdt
  fun switchBy(project: Project?, offset: Int) {
    val open = openTabs()
    if (open.size < 2) return
    val index = open.indexOfFirst { it === project }.coerceAtLeast(0)
    switchTo(open[Math.floorMod(index + offset, open.size)])
  }

  /** Moves the tab of [project] to [gap] between the tabs (0 before the first), in every window. */
  @RequiresEdt
  fun move(project: Project, gap: Int) {
    val open = openTabs()
    val moved = ProjectTabs.moved(open, open.indexOfFirst { it === project }, gap)
    if (moved == open) return
    val rest = openOrder.filter { p -> moved.none { it === p } }
    openOrder.clear()
    openOrder += moved
    openOrder += rest
    refresh()
  }

  private fun openTabs(): List<Project> = openOrder.filter { p -> !p.isDisposed && strips.any { it.project === p } }

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
    val open = openTabs().map { OpenProject(it, it.name, it.presentableUrl) }
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
