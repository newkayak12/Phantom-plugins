// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.tabs.core

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.fileEditor.impl.EditorWindowHolder
import com.intellij.openapi.fileEditor.impl.EditorsSplitters
import com.intellij.openapi.fileEditor.impl.FileEditorManagerImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.containers.ContainerUtil
import org.jetbrains.annotations.TestOnly
import java.awt.Component
import java.awt.Container
import java.util.WeakHashMap

/** The tab groups of a project: one controller per editor pane, saved in the workspace in the order of the panes. */
@Service(Service.Level.PROJECT)
@State(name = "PhantomTabGroups", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class TabGroupsService(private val project: Project) : PersistentStateComponent<TabGroupsState> {
  private val controllers = WeakHashMap<EditorWindow, TabGroupPaneController>()
  private var saved = TabGroupsState()

  // read off the EDT: by the color provider and when settings are saved
  @Volatile private var stateSnapshot: TabGroupsState? = null
  @Volatile private var colorSnapshot: Map<String, TabGroupColor> = emptyMap()
  @Volatile private var titleSnapshot: Map<String, GroupTitle> = emptyMap()

  init {
    synchronized(live) { live.add(this) }
  }

  @RequiresEdt
  fun controllerFor(window: EditorWindow): TabGroupPaneController = controllers.getOrPut(window) {
    // the controller holds its window, so the weak keys alone would never let a closed split go
    controllers.keys.removeIf { it.isDisposed }
    val index = windows().indexOf(window)
    TabGroupPaneController(window, TabGroupModel(), saved.panes.getOrNull(index), ::onChanged)
  }

  @RequiresEdt
  private fun onChanged() {
    val live = controllers.filterKeys { !it.isDisposed }
    stateSnapshot = TabGroupsState().apply {
      for (window in windows()) panes.add(live[window]?.model?.toPaneState() ?: PaneState())
    }
    val colors = HashMap<String, TabGroupColor>()
    for (controller in live.values) {
      for (group in controller.model.groups) group.members.forEach { colors.putIfAbsent(it, group.color) }
    }
    colorSnapshot = colors
    val titles = HashMap<String, GroupTitle>()
    for (controller in live.values) controller.titles.forEach(titles::putIfAbsent)
    titleSnapshot = titles
  }

  @get:TestOnly
  val controllerCount: Int
    get() = controllers.size

  /** Controllers of the open panes, made for panes that have none yet. */
  @RequiresEdt
  fun allControllers(): List<TabGroupPaneController> {
    controllers.keys.removeIf { it.isDisposed }
    return windows().filter { !it.isDisposed }.map(::controllerFor)
  }

  /** Controllers made so far for open panes; panes without one have no groups. */
  @RequiresEdt
  fun existingControllers(): List<TabGroupPaneController> = controllers.filterKeys { !it.isDisposed }.values.toList()

  /** [oldUrl], a file or a folder, was renamed or moved to [newUrl]. */
  @RequiresEdt
  fun fileMoved(oldUrl: String, newUrl: String) {
    for (controller in controllers.values) controller.model.rename(oldUrl, newUrl)
  }

  /** The color of the first group [file] is in, in any pane. */
  fun groupColorOf(file: VirtualFile): TabGroupColor? = colorSnapshot[file.url]

  /** What [file]'s tab shows when it is the first tab of a group (in any pane). */
  fun groupTitleOf(file: VirtualFile): GroupTitle? = titleSnapshot[file.url]

  fun isGrouped(file: VirtualFile): Boolean = groupColorOf(file) != null

  /** Panes in screen order (the order of the splitter tree), which is also the order they are restored in. */
  private fun windows(): List<EditorWindow> =
    (FileEditorManagerEx.getInstanceEx(project) as? FileEditorManagerImpl)?.getAllSplitters()?.flatMap(::orderedWindows)
    ?: FileEditorManagerEx.getInstanceEx(project).windows.toList()

  override fun getState(): TabGroupsState = stateSnapshot ?: saved

  override fun loadState(state: TabGroupsState) {
    saved = state
  }

  companion object {
    private val live = ContainerUtil.createWeakSet<TabGroupsService>()

    fun getInstance(project: Project): TabGroupsService = project.service()

    /** Whether [file] is in a group in any pane of any project. */
    fun isGroupedAnywhere(file: VirtualFile): Boolean = synchronized(live) { live.toList() }.any { it.isGrouped(file) }
  }
}

/** The panes of [splitters] in the order of its splitter tree: first component before second, depth first. */
private fun orderedWindows(splitters: EditorsSplitters): List<EditorWindow> {
  val windows = splitters.getWindows()
  val result = ArrayList<EditorWindow>()
  fun collect(component: Component) {
    when (component) {
      is EditorWindowHolder -> component.editorWindow.takeIf { it in windows && it !in result }?.let(result::add)
      is Splitter -> {
        component.firstComponent?.let(::collect)
        component.secondComponent?.let(::collect)
      }
      is Container -> component.components.forEach(::collect)
    }
  }
  collect(splitters)
  // panes not found in the tree (being built or taken apart) go last
  windows.filterTo(result) { it !in result }
  return result
}
