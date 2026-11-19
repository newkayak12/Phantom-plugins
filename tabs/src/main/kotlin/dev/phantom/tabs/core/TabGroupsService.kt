// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.tabs.core

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
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
import java.util.Collections
import java.util.WeakHashMap

/** The tab groups of a project: one controller per editor pane, saved in the workspace in the order of the panes. */
@Service(Service.Level.PROJECT)
@State(name = "PhantomTabGroups", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class TabGroupsService(private val project: Project) : PersistentStateComponent<TabGroupsState>, Disposable {
  private val controllers = WeakHashMap<EditorWindow, TabGroupPaneController>()
  private var saved = TabGroupsState()
  private val unbound: MutableSet<EditorWindow> = Collections.newSetFromMap(WeakHashMap())
  private val restoredPanes: MutableSet<EditorWindow> = Collections.newSetFromMap(WeakHashMap())

  // read off the EDT: by the color provider and when settings are saved
  @Volatile private var stateSnapshot: TabGroupsState? = null
  // by file URL, for files that look the same in every pane they are open in (the providers are not told the pane)
  @Volatile private var colorSnapshot: Map<String, TabGroupColor> = emptyMap()
  @Volatile private var titleSnapshot: Map<String, GroupTitle> = emptyMap()
  // files in a group in one pane and not in another they are open in: those panes set the tab's title and color themselves
  @Volatile private var paneSpecific: Set<String> = emptySet()
  @Volatile private var grouped: Set<String> = emptySet()

  init {
    synchronized(live) { live.add(this) }
  }

  @RequiresEdt
  fun controllerFor(window: EditorWindow): TabGroupPaneController = controllers.getOrPut(window) {
    // the controller holds its window, so the weak keys alone would never let a closed split go
    controllers.keys.removeIf { it.isDisposed }
    val index = windows().indexOf(window)
    // a pane the editor manager does not list yet (still being restored) gets its saved groups once it does
    if (index < 0) unbound.add(window)
    TabGroupPaneController(window, TabGroupModel(), saved.panes.getOrNull(index), ::onChanged, ::isPaneSpecific, this)
  }

  /** A pane's groups changed; [changed] are the files whose group color or group title changed in it. */
  @RequiresEdt
  private fun onChanged(changed: Collection<String>) {
    val live = controllers.filterKeys { !it.isDisposed }
    val panes = windows()
    // no panes known yet (the editors are still being restored): keep what was saved rather than save no panes
    if (panes.isNotEmpty()) {
      stateSnapshot = TabGroupsState().apply {
        for (window in panes) this.panes.add(live[window]?.model?.toPaneState() ?: PaneState())
      }
    }
    // what each pane shows for each file open in it
    val shown = HashMap<String, MutableSet<Pair<GroupTitle?, TabGroupColor?>>>()
    for (window in (panes + live.keys).distinct()) {
      val controller = live[window]
      for (url in window.openUrls()) shown.getOrPut(url, ::HashSet).add(controller?.titles?.get(url) to controller?.colorOf(url))
    }
    val specific = shown.filterValues { it.size > 1 }.keys
    val colors = HashMap<String, TabGroupColor>()
    val titles = HashMap<String, GroupTitle>()
    for ((url, values) in shown) {
      val (title, color) = values.singleOrNull() ?: continue
      color?.let { colors[url] = it }
      title?.let { titles[url] = it }
    }
    val oldColors = colorSnapshot
    val oldTitles = titleSnapshot
    val oldSpecific = paneSpecific
    colorSnapshot = colors
    titleSnapshot = titles
    paneSpecific = specific
    grouped = live.values.flatMapTo(HashSet()) { controller -> controller.model.groups.flatMap { it.members } }

    // what the providers say changed for these files, in every pane, or the panes have to set them now
    val urls = LinkedHashSet(changed)
    for (url in oldColors.keys + colors.keys) if (oldColors[url] != colors[url]) urls.add(url)
    for (url in oldTitles.keys + titles.keys) if (oldTitles[url] != titles[url]) urls.add(url)
    for (url in oldSpecific + specific) if ((url in oldSpecific) != (url in specific)) urls.add(url)
    if (urls.isEmpty()) return
    for (controller in live.values) controller.applyPresentation(urls)
    // the platform reads the providers again (tab background, title, foreground) in every window, detached ones too
    val manager = FileEditorManager.getInstance(project)
    val files = panes.flatMap { it.openFiles() }.filter { it.url in urls && it.url !in specific }.distinct()
    files.forEach(manager::updateFilePresentation)
  }

  /**
   * Whether this project has groups, saved or made since it opened. Until then a pane needs no controller: editor events
   * leave the panes alone, so a project without groups loads nothing of the tab bar code when it opens.
   */
  @get:RequiresEdt
  val isInUse: Boolean
    get() = controllers.isNotEmpty() || hasSavedGroups

  /** Whether the project was saved with groups. */
  val hasSavedGroups: Boolean
    get() = saved.panes.any { it.groups.isNotEmpty() }

  /** Whether [url] is in a group in one pane and not in another it is open in (or shown differently there). */
  fun isPaneSpecific(url: String): Boolean = url in paneSpecific

  @get:TestOnly
  val controllerCount: Int
    get() = controllers.size

  /** Controllers of the open panes, made for panes that have none yet. */
  @RequiresEdt
  fun allControllers(): List<TabGroupPaneController> {
    controllers.keys.removeIf { it.isDisposed }
    val windows = windows()
    for (window in unbound.toList()) {
      val index = windows.indexOf(window)
      if (index < 0) continue
      unbound.remove(window)
      controllers[window]?.bindSaved(saved.panes.getOrNull(index))
    }
    return windows.filter { !it.isDisposed }.map(::controllerFor)
  }

  /**
   * Brings back the saved groups of the panes the editor manager lists, without waiting for an editor event: the editors
   * of a reopened project can all come back before the plugin can see their panes, and then no event follows until the
   * user clicks a tab. Each pane is brought back once, when it is first listed; files of its groups that open later come
   * with editor events. Returns whether nothing is left to wait for: no saved groups, or every saved pane up to the last
   * one with groups is listed.
   */
  @RequiresEdt
  fun restorePanes(): Boolean {
    if (project.isDisposed) return true
    val needed = saved.panes.indexOfLast { it.groups.isNotEmpty() } + 1
    if (needed == 0) return true
    // a pane is listed once its tabs are in it; an empty pane (one made for a file opened before the restore) does not count
    val listed = windows().count { it.tabCount > 0 }
    if (listed == 0) return false
    for (controller in allControllers()) {
      if (controller.window.tabCount > 0 && restoredPanes.add(controller.window)) controller.refresh()
    }
    return listed >= needed
  }

  /** Controllers made so far for open panes; panes without one have no groups. */
  @RequiresEdt
  fun existingControllers(): List<TabGroupPaneController> = controllers.filterKeys { !it.isDisposed }.values.toList()

  /** [oldUrl], a file or a folder, was renamed or moved to [newUrl]. */
  @RequiresEdt
  fun fileMoved(oldUrl: String, newUrl: String) {
    for (controller in controllers.values) controller.model.rename(oldUrl, newUrl)
  }

  /** The color of [file]'s group, when it is in the same group in every pane it is open in. */
  fun groupColorOf(file: VirtualFile): TabGroupColor? = colorSnapshot[file.url]

  /** What [file]'s tab shows as the first tab of a group, when it shows that in every pane it is open in. */
  fun groupTitleOf(file: VirtualFile): GroupTitle? = titleSnapshot[file.url]

  /** Whether [file] is in a group in any pane. */
  fun isGrouped(file: VirtualFile): Boolean = file.url in grouped

  /** Panes in screen order (the order of the splitter tree), which is also the order they are restored in. */
  private fun windows(): List<EditorWindow> =
    (FileEditorManagerEx.getInstanceEx(project) as? FileEditorManagerImpl)?.getAllSplitters()?.flatMap(::orderedWindows)
    ?: FileEditorManagerEx.getInstanceEx(project).windows.toList()

  /** The parent of what the pane controllers install on their tab bars (the group lines); nothing else to free. */
  override fun dispose() {}

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

private fun EditorWindow.openFiles(): List<VirtualFile> = tabbedPane.tabs.tabs.mapNotNull { it.`object` as? VirtualFile }

private fun EditorWindow.openUrls(): List<String> = openFiles().map { it.url }

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
