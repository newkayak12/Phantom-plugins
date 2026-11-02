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
import java.awt.Point
import java.awt.Rectangle
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
 *
 * A [detachProject]ed project leaves the stack and the tabs: its window is free (never hidden, moved or resized by the
 * stack) and its strip is hidden, until [attachProject] puts it back as the last tab.
 *
 * [openSideBySide] shows two project windows side by side over the stack's area ([WindowStack.split]); both of their
 * tabs are highlighted in every strip until it ends.
 */
@Service(Service.Level.APP)
class ProjectTabsService : Disposable {
  private val stack = WindowStack()
  private val strips = ArrayList<ProjectTabStrip>()
  private val openOrder = ArrayList<Project>()

  // projects taken out of the stack into a window of their own, until attached again or closed (not kept on restart)
  private val detached = ArrayList<Project>()

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
    if (strips.none { it.project === strip.project }) detached.removeIf { it === strip.project }
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
    if (isEnabled) refreshAfter { stack.activated(window) }
  }

  /** Records a move of [window] when it happens; true when it leads and [flushMoves] should follow (debounced). */
  @RequiresEdt
  internal fun windowMoved(window: StackedWindow): Boolean = isEnabled && stack.moved(window)

  /** Pushes the pending move; true when it is still pending (the window in front may be entering full screen). */
  @RequiresEdt
  internal fun flushMoves(): Boolean {
    if (!isEnabled) return false
    // maximizing or full screen of a window side by side ends side by side
    refreshAfter { stack.flush() }
    return stack.hasPendingMove
  }

  /** Runs [change] and refreshes the strips when it ended side by side. */
  private fun refreshAfter(change: () -> Unit) {
    val before = stack.splitPair
    change()
    if (stack.splitPair != before) refresh()
  }

  @RequiresEdt
  internal fun settingChanged() {
    if (!isEnabled) stack.unsplit()
    stack.single = isEnabled
    if (isEnabled) {
      val windows = strips.filter { !isDetached(it.project) }.mapNotNull { it.window }
      val leader = windows.firstOrNull { it.isActive } ?: windows.firstOrNull { it === stack.front } ?: windows.firstOrNull()
      leader?.let(stack::lead)
    }
    refresh()
  }

  /** Brings the window of [project] to front, in place. False when the project has no window here. */
  @RequiresEdt
  fun switchTo(project: Project): Boolean {
    val window = strips.firstOrNull { it.project === project }?.window ?: return false
    if (isEnabled && !isDetached(project)) {
      // before the window comes to front, so it shows with the layout already; not to a window already on screen beside it
      if (!stack.isInSplit(window)) strips.firstOrNull { it.window === stack.front }?.project?.let { ToolWindowLayoutSync.copy(it, project) }
      refreshAfter { stack.switchTo(window) }
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

  private fun openTabs(): List<Project> =
    openOrder.filter { p -> !p.isDisposed && strips.any { it.project === p } && !isDetached(p) }

  fun isDetached(project: Project): Boolean = detached.any { it === project }

  /** Whether [project] is one of the project tabs and another tab is left when it is detached. */
  fun canDetach(project: Project): Boolean = isEnabled && openTabs().let { tabs -> tabs.size > 1 && tabs.any { it === project } }

  /**
   * Takes [project] out of the tabs into a window of its own: the stack lets go of its window, which is shown, put
   * [DETACH_STEP][WindowStack.DETACH_STEP] apart from the stack (moved by [moveBy] instead when a tab is dragged out) and
   * brought to front. When it was the tab in front, the next tab comes to front in the stack.
   */
  @RequiresEdt
  fun detachProject(project: Project, moveBy: Point? = null) {
    if (!canDetach(project)) return
    val tabs = openTabs()
    val window = strips.firstOrNull { it.project === project }?.window
    val wasFront = window != null && window === stack.front
    // side by side, the other one of the pair takes the place
    val partner = window?.takeIf(stack::isInSplit)?.let { w -> stack.splitPair?.toList()?.firstOrNull { it !== w } }
    detached += project
    if (window != null) {
      stack.remove(window)
      if (!window.isShown) window.show(true)
      val state = window.state
      if (!state.fullScreen && !window.isInTransition) {
        val bounds = if (moveBy != null) Rectangle(state.bounds).apply { translate(moveBy.x, moveBy.y) }
        else WindowStack.cascaded(state.bounds, WindowStack.DETACH_STEP * detached.size, window.screen)
        window.apply(WindowState(bounds, maximized = false, fullScreen = false))
      }
    }
    val next = partner?.let { p -> strips.firstOrNull { it.window === p }?.project } ?: if (wasFront) ProjectTabs.neighbor(tabs, project) else null
    next?.let(::switchTo)
    window?.activate()
    refresh()
  }

  /** The projects side by side, left first; empty when there are none. */
  fun sideBySide(): List<Project> =
    stack.splitPair?.toList()?.mapNotNull { w -> strips.firstOrNull { it.window === w }?.project } ?: emptyList()

  /** Whether [partner] can be shown side by side with [current]: two different project tabs with a window each. */
  fun canOpenSideBySide(current: Project, partner: Project): Boolean {
    if (!isEnabled || current === partner) return false
    val tabs = openTabs()
    if (tabs.none { it === current } || tabs.none { it === partner }) return false
    val pair = sideBySide()
    if (pair.any { it === current } && pair.any { it === partner }) return false
    return windowOf(current) != null && windowOf(partner) != null
  }

  /** Shows [current] on the left half of the stack's area and [partner] on the right half. */
  @RequiresEdt
  fun openSideBySide(current: Project, partner: Project) {
    if (!canOpenSideBySide(current, partner)) return
    val left = windowOf(current) ?: return
    val right = windowOf(partner) ?: return
    if (stack.split(left, right)) refresh()
  }

  /** Ends side by side: [keep] (else the one in front of the two) takes the whole stack again. */
  @RequiresEdt
  fun exitSideBySide(keep: Project? = null) {
    refreshAfter { stack.unsplit(keep?.let(::windowOf)) }
  }

  private fun windowOf(project: Project): FrameWindow? = strips.firstOrNull { it.project === project }?.window

  /** Puts a detached [project] back into the tabs, as the last one, and brings it to front in the stack. */
  @RequiresEdt
  fun attachProject(project: Project) {
    if (!isEnabled || !detached.removeIf { it === project }) return
    val order = ProjectTabs.attached(openOrder, project)
    openOrder.clear()
    openOrder += order
    val window = strips.firstOrNull { it.project === project }?.window
    if (window != null) {
      // the layout of the tab in front, before the window joins and becomes the front itself
      strips.firstOrNull { it.window === stack.front }?.project?.let { ToolWindowLayoutSync.copy(it, project) }
      stack.add(window)
      stack.switchTo(window)
    }
    refresh()
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
    refreshLater {
      openOrder.removeIf { it === project }
      detached.removeIf { it === project }
    }
  }

  internal fun refreshLater(before: () -> Unit = {}) {
    ApplicationManager.getApplication().invokeLater({ before(); refresh() }, ModalityState.any())
  }

  @RequiresEdt
  private fun refresh() {
    val open = openTabs().map { OpenProject(it, it.name, it.presentableUrl) }
    val visible = ProjectTabs.isStripVisible(isEnabled, open.size)
    val sideBySide = sideBySide()
    val home = SystemProperties.getUserHome()
    for (strip in strips) {
      if (isDetached(strip.project)) {
        // a window of its own: no strip; Window | Attach to Project Tabs takes it back
        strip.display(ProjectTabs.tabs(listOf(OpenProject(strip.project, strip.project.name, strip.project.presentableUrl)), strip.project, home), false)
      }
      else {
        strip.display(ProjectTabs.tabs(open, strip.project, home, sideBySide), visible)
      }
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
