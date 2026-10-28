// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.sidebar

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AiState
import dev.phantom.ai.core.PhantomAiBundle
import dev.phantom.ai.core.SessionListener
import dev.phantom.ai.core.SessionStore
import dev.phantom.ai.core.bestEffort
import org.jetbrains.annotations.TestOnly
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.RenderingHints
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Icon
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeCellRenderer
import javax.swing.tree.TreePath

/** Colors of the B mockup: session status dots and the open-PR pill. */
object SidebarColors {
  val WORKING = Color(0xe8b339)
  val WAITING = Color(0xe06c5a)
  val DONE = Color(0x5fb865)
  val LAUNCHED = Color(0x8a8f98)
  val PR_FG = Color(0x8fb0ff)
  val PR_BG = Color(0x2c3550)

  fun dot(statusKey: String): Color = when (statusKey) {
    "working" -> WORKING
    "waiting" -> WAITING
    "done" -> DONE
    else -> LAUNCHED
  }
}

/**
 * The sidebar of the current project: worktree rows (title or branch, an open-PR pill `#N`) with their session rows below
 * (status dot, title, agent chip). One repository shows its worktrees at the top; several show a repository row above
 * each. Worktrees are read off the EDT through [WorktreeSource] and refreshed when a session or the shared state changes.
 * There is no drag and drop, and no group or repository editing: the repositories are the project's own ([ProjectRepos]).
 */
class WorktreeTreePanel(
  private val project: Project,
  private val worktrees: WorktreeSource = GitWorktreeSource(project),
  private val prTitles: PrTitles = CachedPrTitles(GithubPrTitles()),
  private val actions: SidebarActions = SidebarActions(project),
  private val background: (Runnable) -> Unit = { ApplicationManager.getApplication().executeOnPooledThread(it) },
  private val creator: WorktreeCreator = WorktreeCreator(),
  private val repos: ProjectRepos = GitProjectRepos(project),
) : JPanel(BorderLayout()), Disposable {
  private val root = DefaultMutableTreeNode()
  private val model = DefaultTreeModel(root)
  private val tree = Tree(model)
  private val center = JPanel(BorderLayout())
  private val emptyLabel = JBLabel(PhantomAiBundle.message("sidebar.no.repo")).apply {
    name = "sidebar.no.repo"
    border = JBUI.Borders.empty(8)
  }
  private val scroll = ScrollPaneFactory.createScrollPane(tree)
  private val refreshQueued = AtomicBoolean()
  private var repoOfWorktree: Map<String, RepoNode> = emptyMap()
  private val known = HashSet<String>() // rows seen before: a new row opens, an old one keeps what the user chose

  init {
    tree.isRootVisible = false
    tree.showsRootHandles = true
    tree.cellRenderer = Renderer()
    val bar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
    bar.add(javax.swing.JButton(PhantomAiBundle.message("sidebar.refresh")).apply { addActionListener { refresh() } })
    add(bar, BorderLayout.NORTH)
    add(center, BorderLayout.CENTER)
    center.add(scroll, BorderLayout.CENTER)
    tree.addMouseListener(object : PopupHandler() {
      override fun invokePopup(c: Component, x: Int, y: Int) {
        val path = tree.getPathForLocation(x, y)
        if (path != null) tree.selectionPath = path
        menuFor(nodeObject(path))?.show(c, x, y)
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
    ApplicationManager.getApplication().messageBus.connect(this)
      .subscribe(SessionStore.TOPIC, SessionListener { _, _ -> refresh() })
    // worktree names and sessions are shared by every window: a change in another window shows here too
    ApplicationManager.getApplication().messageBus.connect(this)
      .subscribe(AiState.TOPIC, dev.phantom.ai.core.AiStateListener { refresh() })
    refresh()
  }

  override fun dispose() = Unit

  @TestOnly internal fun treeForTest(): JTree = tree

  @TestOnly internal fun popupFor(row: Int): JPopupMenu? = menuFor(nodeObject(tree.getPathForRow(row)))

  @TestOnly internal fun showsEmptyText(): Boolean = emptyLabel.parent === center

  /**
   * The worktree the toolbar's + Claude / + Codex start in: the selected worktree row, else the worktree of the selected
   * session, else the first main worktree (the first worktree when none is main); null while there are none.
   */
  fun selectedWorktree(): String? {
    val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
    when (val o = node?.userObject) {
      is WorktreeNode -> return o.path
      is SessionRow -> (node.parent as? DefaultMutableTreeNode)?.let { (it.userObject as? WorktreeNode)?.path }?.let { return it }
      is RepoNode -> (o.worktrees.firstOrNull { it.isMain } ?: o.worktrees.firstOrNull())?.let { return it.path }
    }
    val all = allWorktrees()
    return (all.firstOrNull { it.isMain } ?: all.firstOrNull())?.path
  }

  private fun allWorktrees(): List<WorktreeNode> =
    root.depthFirstEnumeration().toList().mapNotNull { (it as DefaultMutableTreeNode).userObject as? WorktreeNode }

  /** Rebuilds the tree: git runs on a pooled thread, the tree is replaced on the EDT. Calls in a burst coalesce. */
  fun refresh() {
    if (!refreshQueued.compareAndSet(false, true)) return
    background(Runnable {
      refreshQueued.set(false)
      val state = AiState.getInstance()
      val built = bestEffort {
        SidebarModel.build(
          repos.roots(), worktrees, SessionStore.getInstance().all(),
          state.state.sessions.mapNotNull(SidebarModel::saved), prTitles,
          { state.state.worktrees.firstOrNull { w -> w.path == it }?.userName }, System.currentTimeMillis(),
        )
      }.getOrNull() ?: return@Runnable
      ApplicationManager.getApplication().invokeLater({ show(built) }, project.disposed)
    })
  }

  private fun show(m: SidebarModel) {
    val expanded = (0 until tree.rowCount).mapNotNull { tree.getPathForRow(it) }.filter { tree.isExpanded(it) }.map { key(it) }.toSet()
    val selected = tree.selectionPath?.let { key(it) }
    root.removeAllChildren()
    val toExpand = ArrayList<DefaultMutableTreeNode>()
    fun worktree(parent: DefaultMutableTreeNode, w: WorktreeNode) {
      val wn = DefaultMutableTreeNode(w).also(parent::add)
      w.sessions.forEach { wn.add(DefaultMutableTreeNode(it)) }
      if (w.sessions.isNotEmpty()) toExpand += wn
    }
    for (r in m.repos) {
      if (m.flat) {
        r.worktrees.forEach { worktree(root, it) }
      } else {
        val rn = DefaultMutableTreeNode(r).also(root::add)
        r.worktrees.forEach { worktree(rn, it) }
        toExpand += rn
      }
    }
    repoOfWorktree = m.repos.flatMap { r -> r.worktrees.map { it.path to r } }.toMap()
    model.reload()
    for (n in toExpand) {
      val p = TreePath(n.path)
      if (key(p) !in known || key(p) in expanded) tree.expandPath(p)
    }
    toExpand.forEach { known += key(TreePath(it.path)) }
    if (selected != null) {
      (0 until tree.rowCount).firstOrNull { key(tree.getPathForRow(it)) == selected }?.let { tree.setSelectionRow(it) }
    }
    val empty = m.repos.isEmpty()
    center.removeAll()
    center.add(if (empty) emptyLabel else scroll, BorderLayout.CENTER)
    center.revalidate()
    center.repaint()
  }

  private fun nodeObject(p: TreePath?) = (p?.lastPathComponent as? DefaultMutableTreeNode)?.userObject

  private fun key(p: TreePath) = p.path.drop(1).joinToString("/") { keyOf((it as DefaultMutableTreeNode).userObject) }

  private fun keyOf(o: Any?): String = when (o) {
    is RepoNode -> o.path
    is WorktreeNode -> o.path
    is SessionRow -> o.session.tabId
    else -> ""
  }

  private fun menu(vararg items: Pair<String, (() -> Unit)?>): JPopupMenu = JPopupMenu().also { m ->
    items.forEach { (t, run) -> m.add(JMenuItem(t).apply { isEnabled = run != null; run?.let { r -> addActionListener { r() } } }) }
  }

  private fun menuFor(o: Any?): JPopupMenu? = when (o) {
    is WorktreeNode -> menu(
      PhantomAiBundle.message("sidebar.open.phantom") to { actions.openInPhantom(o.path) },
      PhantomAiBundle.message("action.new.claude") to { actions.newSession(o.path, AgentKind.CLAUDE) },
      PhantomAiBundle.message("action.new.codex") to { actions.newSession(o.path, AgentKind.CODEX) },
      PhantomAiBundle.message("sidebar.rename") to { renameWorktree(o) },
      PhantomAiBundle.message("worktree.new") to (repoOfWorktree[o.path]?.let { r -> { newWorktree(r) } }),
      PhantomAiBundle.message("worktree.remove") to (if (o.isMain) null else ({ removeWorktree(o) })),
    )
    is RepoNode -> menu(PhantomAiBundle.message("worktree.new") to { newWorktree(o) })
    is SessionRow -> menu(
      PhantomAiBundle.message("action.resume") to (if (actions.canActivate(o.session)) ({ actions.activate(o.session); Unit }) else null),
    )
    else -> null
  }

  private fun activateSelected() {
    val row = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? SessionRow ?: return
    if (actions.canActivate(row.session)) actions.activate(row.session)
  }

  private fun renameWorktree(w: WorktreeNode) {
    val name = Messages.showInputDialog(project, PhantomAiBundle.message("sidebar.worktree.name"), PhantomAiBundle.message("sidebar.rename"), null, w.title, null) ?: return
    AiState.getInstance().worktree(w.path).userName = name.trim().ifEmpty { null }
    AiState.getInstance().changed()
    refresh()
  }

  /** Orca's new workspace: a worktree on a new branch (git off the EDT), then the chosen agent started in it. */
  private fun newWorktree(r: RepoNode) {
    val repo = r.path
    background(Runnable {
      val base = creator.defaultBase(repo)
      later {
        val dialog = NewWorktreeDialog(project, r.name, base)
        if (!dialog.showAndGet()) return@later
        val request = dialog.request()
        background(Runnable {
          val created = creator.create(repo, request.name, request.base)
          later {
            created.onSuccess { w ->
              refresh()
              request.agent?.let { actions.newSession(w.path, it) }
            }.onFailure { Messages.showErrorDialog(project, it.message, PhantomAiBundle.message("worktree.new.failed")) }
          }
        })
      }
    })
  }

  /** Removes a worktree and its branch if merged; changed files need a second, forced confirmation, as in Orca. */
  private fun removeWorktree(w: WorktreeNode) {
    val repo = repoOfWorktree[w.path]?.path ?: return
    if (SessionStore.getInstance().forWorktree(w.path).isNotEmpty()) {
      Messages.showInfoMessage(project, PhantomAiBundle.message("worktree.remove.busy"), PhantomAiBundle.message("worktree.remove"))
      return
    }
    if (Messages.showYesNoDialog(project, PhantomAiBundle.message("worktree.remove.confirm", w.path, w.branch ?: "-"),
        PhantomAiBundle.message("worktree.remove"), null) != Messages.YES) return
    fun run(force: Boolean): Unit = background(Runnable {
      val error = creator.remove(repo, w.path, w.branch, force)
      later {
        when {
          error == null -> refresh()
          !force && Messages.showYesNoDialog(project, PhantomAiBundle.message("worktree.remove.force", error),
            PhantomAiBundle.message("worktree.remove"), null) == Messages.YES -> run(true)
          force -> Messages.showErrorDialog(project, error, PhantomAiBundle.message("worktree.remove"))
        }
      }
    })
    run(false)
  }

  private fun later(run: () -> Unit) = ApplicationManager.getApplication().invokeLater(run, project.disposed)

  /** A filled circle; [color] is the status of the session. */
  private class Dot(private val color: Color) : Icon {
    override fun getIconWidth() = 10
    override fun getIconHeight() = 10
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
      val g2 = g.create() as java.awt.Graphics2D
      try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = color
        g2.fillOval(x + 1, y + 1, 8, 8)
      } finally {
        g2.dispose()
      }
    }
  }

  /** One row: [dot] title [branch] [#N pill] [chip] [elapsed]. */
  private class Renderer : JPanel(FlowLayout(FlowLayout.LEFT, 4, 1)), TreeCellRenderer {
    private val icon = JBLabel()
    private val title = JBLabel()
    private val hint = JBLabel()
    private val pill = JBLabel().apply {
      isOpaque = true
      foreground = SidebarColors.PR_FG
      background = SidebarColors.PR_BG
      border = JBUI.Borders.empty(0, 5)
    }
    private val chip = JBLabel().apply { border = JBUI.Borders.empty(0, 4) }
    private val elapsed = JBLabel()

    init {
      listOf(icon, title, hint, pill, chip, elapsed).forEach(::add)
    }

    override fun getTreeCellRendererComponent(
      tree: JTree, value: Any?, sel: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
    ): Component {
      val fg = if (sel) UIManagerColors.selectionFg() else tree.foreground
      val dim = if (sel) fg else com.intellij.util.ui.UIUtil.getContextHelpForeground()
      isOpaque = sel
      background = if (sel) UIManagerColors.selectionBg() else tree.background
      title.foreground = fg
      hint.foreground = dim
      chip.foreground = dim
      elapsed.foreground = dim
      listOf(hint, pill, chip, elapsed).forEach { it.text = ""; it.isVisible = false }
      when (val o = (value as? DefaultMutableTreeNode)?.userObject) {
        is RepoNode -> {
          title.text = o.name
          icon.icon = com.intellij.icons.AllIcons.Nodes.Module
        }
        is WorktreeNode -> {
          title.text = o.title
          icon.icon = com.intellij.icons.AllIcons.Vcs.Branch
          o.branch?.takeIf { it != o.title }?.let { hint.text = it; hint.isVisible = true }
          o.prNumber?.let { pill.text = "#$it"; pill.isVisible = true }
        }
        is SessionRow -> {
          title.text = o.title
          icon.icon = Dot(SidebarColors.dot(o.statusKey))
          chip.text = o.agentIconKey
          chip.isVisible = true
          o.elapsed?.let { elapsed.text = it; elapsed.isVisible = true }
        }
        else -> {
          title.text = ""
          icon.icon = null
        }
      }
      return this
    }
  }

  private object UIManagerColors {
    fun selectionFg(): Color = javax.swing.UIManager.getColor("Tree.selectionForeground") ?: Color.WHITE
    fun selectionBg(): Color = javax.swing.UIManager.getColor("Tree.selectionBackground") ?: Color.GRAY
  }
}
