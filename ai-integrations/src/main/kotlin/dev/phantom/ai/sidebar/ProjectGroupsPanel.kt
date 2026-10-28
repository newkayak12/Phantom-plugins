// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.sidebar

import dev.phantom.ai.core.rethrowIfCancellation
import dev.phantom.ai.core.bestEffort
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentSession
import dev.phantom.ai.core.AiState
import dev.phantom.ai.core.GroupTree
import dev.phantom.ai.core.PhantomAiBundle
import dev.phantom.ai.core.SessionStore
import org.jetbrains.annotations.TestOnly
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JMenuItem
import javax.swing.JTree
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * The "Projects" tab: group > repo > worktree > session. Groups are edited and persisted in [AiState]; the worktrees
 * are read off the EDT through [WorktreeSource] and refreshed when a session changes.
 */
class ProjectGroupsPanel(
  private val project: Project,
  private val worktrees: WorktreeSource = GitWorktreeSource(project),
  private val prTitles: PrTitles = CachedPrTitles(GithubPrTitles()),
  private val actions: SidebarActions = SidebarActions(project),
  private val background: (Runnable) -> Unit = { ApplicationManager.getApplication().executeOnPooledThread(it) },
) : JPanel(BorderLayout()), Disposable {
  private val root = DefaultMutableTreeNode()
  private val model = DefaultTreeModel(root)
  private val tree = Tree(model)
  private val refreshQueued = AtomicBoolean()
  private var restoring = false

  init {
    tree.isRootVisible = false
    tree.showsRootHandles = true
    tree.cellRenderer = Renderer()
    val bar = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 2))
    bar.add(button(PhantomAiBundle.message("sidebar.new.group")) { newGroup(null) })
    bar.add(button(PhantomAiBundle.message("sidebar.add.repo")) { addRepo(null) })
    bar.add(button(PhantomAiBundle.message("sidebar.refresh")) { refresh() })
    add(bar, BorderLayout.NORTH)
    add(ScrollPaneFactory.createScrollPane(tree), BorderLayout.CENTER)
    installDnd()
    tree.addMouseListener(object : PopupHandler() {
      override fun invokePopup(c: Component, x: Int, y: Int) {
        val path = tree.getPathForLocation(x, y)
        if (path != null) tree.selectionPath = path
        menuFor((path?.lastPathComponent as? DefaultMutableTreeNode)?.userObject)?.show(c, x, y)
      }
    })
    tree.addMouseListener(object : MouseAdapter() {
      override fun mouseClicked(e: MouseEvent) {
        if (e.clickCount == 2) activateSelected()
      }
    })
    tree.addKeyListener(object : KeyAdapter() {
      override fun keyPressed(e: KeyEvent) {
        if (e.keyCode == KeyEvent.VK_ENTER) activateSelected()
      }
    })
    tree.addTreeExpansionListener(object : TreeExpansionListener {
      override fun treeExpanded(e: TreeExpansionEvent) = collapsed(e, false)
      override fun treeCollapsed(e: TreeExpansionEvent) = collapsed(e, true)
    })
    ApplicationManager.getApplication().messageBus.connect(this)
      .subscribe(SessionStore.TOPIC, dev.phantom.ai.core.SessionListener { _, _ -> refresh() })
    refresh()
  }

  override fun dispose() = Unit

  /** Drag a group or repo onto a group (nest / move into it), onto a repo (a repo goes before it), or onto blank space (root). */
  private fun installDnd() {
    tree.dragEnabled = !java.awt.GraphicsEnvironment.isHeadless() // setDragEnabled throws HeadlessException on CI
    tree.dropMode = javax.swing.DropMode.ON
    tree.transferHandler = object : javax.swing.TransferHandler() {
      override fun getSourceActions(c: javax.swing.JComponent) = MOVE
      override fun createTransferable(c: javax.swing.JComponent): java.awt.datatransfer.Transferable? =
        dragKey((tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject)
          ?.let { java.awt.datatransfer.StringSelection(it) }

      override fun canImport(support: TransferSupport) =
        support.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor) &&
          (support.dropLocation as? javax.swing.JTree.DropLocation)?.path.let { it == null || dragKey(nodeObject(it)) != null } &&
          dropAllowed(support)

      private fun dropAllowed(support: TransferSupport): Boolean = try {
        val source = support.transferable.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
        val target = dragKey(nodeObject((support.dropLocation as? javax.swing.JTree.DropLocation)?.path))
        source == null || GroupEdits.canDrop(AiState.getInstance().tree(), source, target)
      } catch (e: Exception) {
        rethrowIfCancellation(e)
        true
      }

      override fun importData(support: TransferSupport): Boolean {
        if (!canImport(support)) return false
        val source = support.transferable.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String ?: return false
        val target = dragKey(nodeObject((support.dropLocation as javax.swing.JTree.DropLocation).path))
        return drop(source, (support.dropLocation as javax.swing.JTree.DropLocation).path)
      }
    }
  }

  /** What a drop does once the handler has the dragged key: false (nothing changes) when it would put a group into its own subtree. */
  internal fun drop(source: String, targetPath: TreePath?): Boolean {
    val target = dragKey(nodeObject(targetPath))
    if (!GroupEdits.canDrop(AiState.getInstance().tree(), source, target)) return false
    edit { GroupEdits.drop(it, source, target) }
    return true
  }

  @TestOnly internal fun treeForTest(): JTree = tree

  @TestOnly internal fun popupFor(row: Int): JPopupMenu? = menuFor(nodeObject(tree.getPathForRow(row)))

  @TestOnly internal fun dragKeyAt(row: Int): String? = dragKey(nodeObject(tree.getPathForRow(row)))

  private fun nodeObject(p: TreePath?) = (p?.lastPathComponent as? DefaultMutableTreeNode)?.userObject

  private fun dragKey(o: Any?): String? = when (o) {
    is GroupNode -> "group:${o.group.id}"
    is RepoNode -> "repo:${o.repo.path}"
    else -> null
  }

  private fun button(text: String, run: () -> Unit) = javax.swing.JButton(text).apply { addActionListener { run() } }

  private fun collapsed(e: TreeExpansionEvent, value: Boolean) {
    if (restoring) return
    val g = ((e.path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? GroupNode)?.group ?: return
    val state = AiState.getInstance()
    state.setTree(GroupEdits.setCollapsed(state.tree(), g.id, value))
  }

  /** Rebuilds the tree: git runs on a pooled thread, the tree is replaced on the EDT. Calls in a burst coalesce. */
  fun refresh() {
    if (!refreshQueued.compareAndSet(false, true)) return
    background(Runnable {
      refreshQueued.set(false)
      val state = AiState.getInstance()
      val built = bestEffort {
        SidebarModel.build(
          state.tree(), worktrees, SessionStore.getInstance().all(),
          state.state.sessions.mapNotNull(SidebarModel::saved), prTitles,
          { state.state.worktrees.firstOrNull { w -> w.path == it }?.userName }, System.currentTimeMillis(),
        )
      }.getOrNull() ?: return@Runnable
      ApplicationManager.getApplication().invokeLater({ show(built) }, project.disposed)
    })
  }

  private fun show(m: SidebarModel) {
    restoring = true
    try {
      val expanded = (0 until tree.rowCount).mapNotNull { tree.getPathForRow(it) }.filter { tree.isExpanded(it) }.map { key(it) }.toSet()
      val first = root.childCount == 0
      root.removeAllChildren()
      val toExpand = ArrayList<DefaultMutableTreeNode>()
      var underCollapsed = 0 // expanding a node expands its ancestors, so nothing below a collapsed group is expanded
      fun repo(parent: DefaultMutableTreeNode, r: RepoNode) {
        val rn = DefaultMutableTreeNode(r).also(parent::add)
        r.worktrees.forEach { w ->
          val wn = DefaultMutableTreeNode(w).also(rn::add)
          w.sessions.forEach { wn.add(DefaultMutableTreeNode(it)) }
          if (w.sessions.isNotEmpty() && underCollapsed == 0) toExpand += wn
        }
        if (underCollapsed == 0) toExpand += rn
      }
      fun group(parent: DefaultMutableTreeNode, g: GroupNode) {
        val gn = DefaultMutableTreeNode(g).also(parent::add)
        if (g.group.collapsed) underCollapsed++
        g.groups.forEach { group(gn, it) }
        g.repos.forEach { repo(gn, it) }
        if (g.group.collapsed) underCollapsed-- else if (underCollapsed == 0) toExpand += gn
      }
      m.groups.forEach { group(root, it) }
      m.ungrouped.forEach { repo(root, it) }
      model.reload()
      for (n in toExpand) {
        val p = TreePath(n.path)
        if (first || key(p) in expanded || n.userObject is GroupNode) tree.expandPath(p)
      }
    } finally {
      restoring = false
    }
  }

  private fun key(p: TreePath) = p.path.drop(1).joinToString("/") { (it as DefaultMutableTreeNode).userObject.toString() }

  private fun menu(vararg items: Pair<String, (() -> Unit)?>): JPopupMenu = JPopupMenu().also { m ->
    items.forEach { (t, run) -> m.add(JMenuItem(t).apply { isEnabled = run != null; run?.let { r -> addActionListener { r() } } }) }
  }

  private fun menuFor(o: Any?): JPopupMenu? = when (o) {
    is GroupNode -> menu(
      PhantomAiBundle.message("sidebar.new.subgroup") to { newGroup(o.group.id) },
      PhantomAiBundle.message("sidebar.add.repo") to { addRepo(o.group.id) },
      PhantomAiBundle.message("sidebar.rename") to { renameGroup(o) },
      PhantomAiBundle.message("sidebar.move.up") to { edit { GroupEdits.moveGroup(it, o.group.id, -1) } },
      PhantomAiBundle.message("sidebar.move.down") to { edit { GroupEdits.moveGroup(it, o.group.id, 1) } },
      PhantomAiBundle.message("sidebar.nest") to { chooseGroup(o.group.id) { id -> edit { GroupEdits.nest(it, o.group.id, id) } } },
      PhantomAiBundle.message("sidebar.delete") to { edit { GroupEdits.delete(it, o.group.id) } },
    )
    is RepoNode -> menu(
      PhantomAiBundle.message("sidebar.move.up") to { edit { GroupEdits.moveRepo(it, o.repo.path, -1) } },
      PhantomAiBundle.message("sidebar.move.down") to { edit { GroupEdits.moveRepo(it, o.repo.path, 1) } },
      PhantomAiBundle.message("sidebar.move.group") to { chooseGroup(null) { id -> edit { GroupEdits.moveRepoToGroup(it, o.repo.path, id) } } },
      PhantomAiBundle.message("sidebar.remove.repo") to { edit { GroupEdits.removeRepo(it, o.repo.path) } },
    )
    is WorktreeNode -> menu(
      PhantomAiBundle.message("sidebar.open.phantom") to { actions.openInPhantom(o.path) },
      PhantomAiBundle.message("action.new.claude") to { actions.newSession(o.path, AgentKind.CLAUDE) },
      PhantomAiBundle.message("action.new.codex") to { actions.newSession(o.path, AgentKind.CODEX) },
      PhantomAiBundle.message("sidebar.rename") to { renameWorktree(o) },
    )
    is SessionRow -> menu(
      PhantomAiBundle.message("action.resume") to (if (actions.canActivate(o.session)) ({ actions.activate(o.session); Unit }) else null),
    )
    else -> null
  }

  private fun activateSelected() {
    val row = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? SessionRow ?: return
    if (actions.canActivate(row.session)) actions.activate(row.session)
  }

  private fun edit(change: (GroupTree) -> GroupTree) {
    val state = AiState.getInstance()
    state.setTree(change(state.tree()))
    refresh()
  }

  private fun newGroup(parent: String?) {
    val name = Messages.showInputDialog(project, PhantomAiBundle.message("sidebar.group.name"), PhantomAiBundle.message("sidebar.new.group"), null) ?: return
    edit { GroupEdits.addGroup(it, name, parent) }
  }

  private fun renameGroup(g: GroupNode) {
    val name = Messages.showInputDialog(project, PhantomAiBundle.message("sidebar.group.name"), PhantomAiBundle.message("sidebar.rename"), null, g.group.name, null) ?: return
    edit { GroupEdits.rename(it, g.group.id, name) }
  }

  private fun renameWorktree(w: WorktreeNode) {
    val name = Messages.showInputDialog(project, PhantomAiBundle.message("sidebar.worktree.name"), PhantomAiBundle.message("sidebar.rename"), null, w.title, null) ?: return
    AiState.getInstance().worktree(w.path).userName = name.trim().ifEmpty { null }
    refresh()
  }

  private fun addRepo(groupId: String?) {
    val dir = FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFolderDescriptor(), project, null) ?: return
    edit { GroupEdits.addRepo(it, dir.path, groupId) }
  }

  /** Pick a group (or none = root) from a popup list; [exclude] is not offered. */
  private fun chooseGroup(exclude: String?, picked: (String?) -> Unit) {
    val groups = AiState.getInstance().tree().groups.filter { it.id != exclude }
    val names = listOf(PhantomAiBundle.message("sidebar.no.group")) + groups.map { it.name }
    JBPopupFactory.getInstance().createPopupChooserBuilder(names)
      .setItemChosenCallback { name -> picked(groups.getOrNull(names.indexOf(name) - 1)?.id) }
      .createPopup().showInCenterOf(this)
  }

  private class Renderer : DefaultTreeCellRenderer() {
    override fun getTreeCellRendererComponent(
      tree: JTree, value: Any?, sel: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
    ): Component {
      super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
      val o = (value as? DefaultMutableTreeNode)?.userObject
      var icon: Icon? = null
      text = when (o) {
        is GroupNode -> o.group.name.also { icon = AllIcons.Nodes.Folder }
        is RepoNode -> o.name.also { icon = AllIcons.Nodes.Module }
        is WorktreeNode -> (o.title + (o.branch?.takeIf { it != o.title }?.let { "  [$it]" } ?: "")).also { icon = AllIcons.Vcs.Branch }
        is SessionRow -> "${o.agentIconKey} ${o.statusKey}: ${o.title}" + (o.elapsed?.let { "  ($it)" } ?: "")
        else -> ""
      }
      if (o is SessionRow) icon = when (o.statusKey) {
        "working" -> AllIcons.Process.Step_1
        "waiting" -> AllIcons.General.BalloonWarning
        "done" -> AllIcons.General.InspectionsOK
        else -> AllIcons.Actions.Execute
      }
      setIcon(icon)
      return this
    }
  }
}
