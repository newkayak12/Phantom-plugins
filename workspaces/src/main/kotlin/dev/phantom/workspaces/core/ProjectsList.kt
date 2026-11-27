// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.workspaces.core

import com.intellij.icons.AllIcons
import com.intellij.ide.RecentProjectsManager
import com.intellij.ide.RecentProjectsManagerBase
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.JBMenuItem
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.wm.ex.WelcomeScreenProjectProvider
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.SystemProperties
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Point
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val CLOSE_WIDTH = 24
private const val INDENT = 20

/**
 * The list in the Projects and Files view of [project]'s frame: the open projects and the recent ones, grouped by
 * repository with every worktree of it ([WorkspaceList]). A click switches to an open project, or opens one as a tab of
 * the window (in Home's place when [project] is Home); the × of an open project closes it; its context menu detaches it
 * or shows it side by side. [searchField] filters by name, location and branch. Reloaded when projects open, close, are
 * reordered or switch branch ([ProjectTabsService]) and when the recent projects change.
 */
class ProjectsList(private val project: Project, parent: Disposable) {
  val searchField: SearchTextField = SearchTextField(false)
  val list: JBList<WorkspaceRow> = JBList<WorkspaceRow>()

  private val model = CollectionListModel<WorkspaceRow>()
  private val service: ProjectTabsService get() = ProjectTabsService.getInstance()
  private var rows: List<WorkspaceRow> = emptyList()

  // repositories whose main checkout has worktrees under it in the list: its row gets the home icon
  private var withWorktrees: Set<String> = emptySet()
  private var loading: Job? = null
  private var disposed = false

  init {
    list.model = model
    list.cellRenderer = Renderer()
    list.selectionMode = ListSelectionModel.SINGLE_SELECTION
    list.emptyText.text = PhantomWorkspacesBundle.message("projects.list.empty")
    searchField.addDocumentListener(object : DocumentAdapter() {
      override fun textChanged(e: DocumentEvent) = show()
    })
    list.addMouseListener(object : MouseAdapter() {
      override fun mousePressed(e: MouseEvent) {
        if (e.isPopupTrigger) showMenu(e.point)
      }

      override fun mouseReleased(e: MouseEvent) {
        if (e.isPopupTrigger) {
          showMenu(e.point)
          return
        }
        val index = indexAt(e.point) ?: return
        val row = model.getElementAt(index)
        val close = row.isOpen && (SwingUtilities.isMiddleMouseButton(e) || SwingUtilities.isLeftMouseButton(e) && isOnClose(e.point, index))
        when {
          close -> service.close(row.key as Project)
          SwingUtilities.isLeftMouseButton(e) && e.clickCount == 1 -> activate(row)
        }
      }
    })
    list.addKeyListener(object : KeyAdapter() {
      override fun keyPressed(e: KeyEvent) {
        if (e.keyCode == KeyEvent.VK_ENTER) list.selectedValue?.let(::activate)
      }
    })
    service.addListener(parent) { reload() }
    ApplicationManager.getApplication().messageBus.connect(parent).subscribe(RecentProjectsManager.RECENT_PROJECTS_CHANGE_TOPIC, object : RecentProjectsManager.RecentProjectsChange {
      override fun change() = reload()
    })
    Disposer.register(parent) {
      disposed = true
      loading?.cancel()
    }
    reload()
  }

  /** The rows shown now, filtered by the search. */
  val shownRows: List<WorkspaceRow> get() = model.items

  /** Reads the git checkouts and worktrees of the open and recent projects off the EDT, then shows them. */
  @RequiresEdt
  fun reload() {
    if (disposed) return
    val open = service.openProjects().mapNotNull { p -> p.basePath?.let { Triple<Any, String, String>(p, it, p.name) } }
    val manager = RecentProjectsManagerBase.getInstanceEx()
    val home = WelcomeScreenProjectProvider.getWelcomeScreenProjectPath()
    val recent = manager.getRecentPaths().filter { home == null || Path.of(it) != home }.map { it to manager.getProjectName(it) }
    loading?.cancel()
    val read = service.knownProjects(open, recent)
    loading = service.scope.launch {
      val known = read.await()
      withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
        if (!disposed) {
          rows = WorkspaceList.rows(known, project, SystemProperties.getUserHome())
          withWorktrees = rows.filter { it.isIndented }.mapNotNullTo(HashSet()) { it.repository }
          show()
        }
      }
    }
  }

  private fun show() {
    val shown = WorkspaceList.filtered(rows, searchField.text)
    if (shown != model.items) model.replaceAll(shown)
    val current = shown.indexOfFirst { it.isCurrent }
    if (current >= 0) list.selectedIndex = current else list.clearSelection()
  }

  private fun activate(row: WorkspaceRow) {
    val open = row.key as? Project
    when {
      open == null -> service.open(Path.of(row.path), project)
      !row.isCurrent -> service.switchTo(open)
    }
  }

  private fun indexAt(point: Point): Int? =
    list.locationToIndex(point).takeIf { it >= 0 && list.getCellBounds(it, it)?.contains(point) == true }

  private fun isOnClose(point: Point, index: Int): Boolean {
    val bounds = list.getCellBounds(index, index) ?: return false
    return point.x >= bounds.x + bounds.width - JBUI.scale(CLOSE_WIDTH)
  }

  private fun showMenu(point: Point) {
    val index = indexAt(point) ?: return
    list.selectedIndex = index
    val row = model.getElementAt(index)
    val other = row.key as? Project
    if (other == null) {
      showDeleteMenu(row, point)
      return
    }
    val menu = JBPopupMenu()
    val detach = JBMenuItem(PhantomWorkspacesBundle.message("action.PhantomWorkspaces.DetachProject.text"))
    detach.isEnabled = service.canDetach(other)
    detach.addActionListener { service.detachProject(other) }
    menu.add(detach)
    menu.addSeparator()
    val sideBySide = JBMenuItem(PhantomWorkspacesBundle.message("tab.menu.open.side.by.side"))
    sideBySide.isEnabled = service.canOpenSideBySide(project, other)
    sideBySide.addActionListener { service.openSideBySide(project, other) }
    menu.add(sideBySide)
    if (service.sideBySide().isNotEmpty()) {
      val exit = JBMenuItem(PhantomWorkspacesBundle.message("tab.menu.exit.side.by.side"))
      // the window whose list it is chosen in stays
      exit.addActionListener { service.exitSideBySide(project) }
      menu.add(exit)
    }
    menu.show(list, point.x, point.y)
  }

  // a folder that is not open: delete it (a worktree through git, anything else to the Trash)
  private fun showDeleteMenu(row: WorkspaceRow, point: Point) {
    val kind = FolderDeletion.of(row, row.repository in withWorktrees, Files.isDirectory(Path.of(row.path)))
    val menu = JBPopupMenu()
    val label = when (kind) {
      FolderDeletion.WORKTREE -> "list.delete.worktree"
      FolderDeletion.MISSING -> "list.delete.missing"
      else -> "list.delete.folder"
    }
    val delete = JBMenuItem(PhantomWorkspacesBundle.message(label))
    delete.isEnabled = kind != FolderDeletion.NONE
    delete.addActionListener { deleteFolder(project, row, kind) { reload() } }
    menu.add(delete)
    menu.show(list, point.x, point.y)
  }

  private inner class Renderer : ListCellRenderer<WorkspaceRow> {
    private val text = object : ColoredListCellRenderer<WorkspaceRow>() {
      override fun customizeCellRenderer(list: JList<out WorkspaceRow>, row: WorkspaceRow, index: Int, selected: Boolean, focus: Boolean) {
        icon = when {
          row.isIndented -> AllIcons.Vcs.Branch
          row.isMain && row.repository in withWorktrees -> AllIcons.Nodes.HomeFolder
          else -> AllIcons.Nodes.Folder
        }
        ipad = JBUI.insetsLeft(if (row.isIndented) INDENT else 0)
        append(row.name, if (row.isOpen) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
        row.branch?.takeIf { it != row.name }?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
        append("  ${row.location}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
      }
    }
    private val close = JBLabel(AllIcons.Actions.Close).apply { border = JBUI.Borders.emptyRight(4) }
    private val panel = JPanel(BorderLayout())

    override fun getListCellRendererComponent(
      list: JList<out WorkspaceRow>, row: WorkspaceRow, index: Int, selected: Boolean, focus: Boolean,
    ): Component {
      val content = text.getListCellRendererComponent(list, row, index, selected, focus)
      panel.removeAll()
      panel.add(content, BorderLayout.CENTER)
      if (row.isOpen) panel.add(close, BorderLayout.EAST)
      panel.background = content.background
      panel.toolTipText = listOfNotNull(row.path, row.branch).joinToString(" · ")
      return panel
    }
  }

  companion object {
    /**
     * The open projects ([open]: key, base path, name), the [recent] ones (path, name) and the other checkouts of the
     * repositories they belong to: the main checkout and every worktree git lists. Paths are real (a symbolic link
     * resolved), so a checkout reached two ways is one. Reads files: not on the EDT.
     */
    internal fun known(open: List<Triple<Any, String, String>>, recent: List<Pair<String, String>>): List<KnownProject> {
      val result = ArrayList<KnownProject>()
      val seen = HashSet<String>()
      fun add(path: String, name: String, key: Any?) {
        val real = real(path)
        if (!seen.add(real) && key == null) return
        val git = GitCheckouts.read(Path.of(real))
        result += KnownProject(real, name, git?.commonDir, git?.branch, git?.isMain == true, key)
      }
      for ((key, path, name) in open) add(path, name, key)
      // a recent project or worktree whose folder is gone (deleted outside the IDE) is not listed
      for ((path, name) in recent) if (Files.isDirectory(Path.of(path))) add(path, name, null)
      for (repository in result.mapNotNull { it.repository }.distinct()) {
        val dir = Path.of(repository)
        GitCheckouts.mainRoot(dir)?.takeIf { Files.isDirectory(it) }?.let { add(it.toString(), it.fileName.toString(), null) }
        for (worktree in GitCheckouts.worktrees(dir)) if (Files.isDirectory(Path.of(worktree.root))) add(worktree.root, Path.of(worktree.root).fileName.toString(), null)
      }
      return result
    }

    private fun real(path: String): String = FileUtil.toSystemIndependentName(
      try {
        Path.of(path).toRealPath().toString()
      }
      catch (_: IOException) {
        path
      }
    )
  }
}
