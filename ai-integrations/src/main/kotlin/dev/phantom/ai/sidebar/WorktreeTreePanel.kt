// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.sidebar

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.IdeFrame
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
import dev.phantom.ai.terminal.submit
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
  private val worktrees: WorktreeSource = SharedWorktrees,
  private val prTitles: PrTitles = SharedPrTitles,
  private val actions: SidebarActions = SidebarActions(project),
  private val background: (Runnable) -> Unit = { ApplicationManager.getApplication().executeOnPooledThread(it) },
  private val creator: WorktreeCreator = WorktreeCreator(),
  private val repos: ProjectRepos = GitProjectRepos(project),
  /** Name, base and agent of a new worktree in the named repository, its default base given; null = cancelled. */
  private val askNewWorktree: (String, String?) -> NewWorktreeRequest? = { name, base ->
    NewWorktreeDialog(project, name, base).let { if (it.showAndGet()) it.request() else null }
  },
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
  // the elapsed times move on while shown: re-timed from the last model, as a full refresh runs git and PR lookups
  private val ticker = javax.swing.Timer(TICK_MS) { if (isShowing) retime(System.currentTimeMillis()) }
  private var repoOfWorktree: Map<String, RepoNode> = emptyMap()
  private var nestedOf: Map<String, NestedWorktree> = emptyMap()
  private val known = HashSet<String>() // rows seen before: a new row opens, an old one keeps what the user chose
  private var shown: SidebarModel? = null
  /** The row under the mouse: a repository or worktree row there shows its + (new worktree) at its end. */
  private var hoveredRow = -1
  private val whenShown = dev.phantom.ai.core.WhenShown(this, ::refresh)

  init {
    tree.isRootVisible = false
    tree.showsRootHandles = true
    tree.cellRenderer = Renderer { hoveredRow }
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
        if (e.clickCount == 1 && plusAt(e.x, e.y)) newWorktreeAt(tree.getClosestRowForLocation(e.x, e.y))
        else if (e.clickCount == 2) activateSelected()
      }

      override fun mouseExited(e: MouseEvent) = hover(-1)
    })
    tree.addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
      override fun mouseMoved(e: MouseEvent) = hover(tree.getRowForLocation(e.x, e.y))
    })
    tree.addKeyListener(object : KeyAdapter() {
      override fun keyPressed(e: KeyEvent) {
        when {
          e.keyCode == KeyEvent.VK_ENTER -> activateSelected()
          e.keyCode == KeyEvent.VK_F2 -> renameSelected()
          // the menu from the keyboard too: the context-menu key or Shift+F10, at the selected row
          e.keyCode == KeyEvent.VK_CONTEXT_MENU || (e.keyCode == KeyEvent.VK_F10 && e.isShiftDown) -> showMenuForSelection()
        }
      }
    })
    ApplicationManager.getApplication().messageBus.connect(this)
      .subscribe(SessionStore.TOPIC, SessionListener { _, _ -> whenShown.request() })
    // worktree names and sessions are shared by every window: a change in another window shows here too
    ApplicationManager.getApplication().messageBus.connect(this)
      .subscribe(AiState.TOPIC, dev.phantom.ai.core.AiStateListener { whenShown.request() })
    // worktrees made or removed outside the IDE show once it is back in front
    ApplicationManager.getApplication().messageBus.connect(this)
      .subscribe(ApplicationActivationListener.TOPIC, object : ApplicationActivationListener {
        override fun applicationActivated(ideFrame: IdeFrame) = whenShown.request()
      })
    whenShown.request()
    ticker.start()
  }

  override fun dispose() = ticker.stop()

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

  private fun hover(row: Int) {
    if (row == hoveredRow) return
    hoveredRow = row
    tree.repaint()
  }

  /** Whether [x], [y] is on the + at the end of a hovered repository or worktree row. */
  @TestOnly internal fun plusAt(x: Int, y: Int): Boolean {
    val row = tree.getRowForLocation(x, y)
    if (row < 0 || row != hoveredRow) return false
    val o = nodeObject(tree.getPathForRow(row))
    if (o !is RepoNode && o !is WorktreeNode) return false
    val bounds = tree.getRowBounds(row) ?: return false
    return x >= bounds.x + bounds.width - PLUS_HIT
  }

  @TestOnly internal fun hoverForTest(row: Int) = hover(row)

  private fun newWorktreeAt(row: Int) {
    when (val o = nodeObject(tree.getPathForRow(row))) {
      is RepoNode -> newWorktree(o)
      is WorktreeNode -> repoOfWorktree[o.path]?.let { newWorktree(it, o.path.takeIf { _ -> !o.isMain }) }
    }
  }

  /**
   * New worktree from the title bar's +: in the repository of the selected row (below a selected worktree, as its
   * menu does), else in the first repository.
   */
  fun newWorktreeForSelection() {
    when (val o = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject) {
      is RepoNode -> return newWorktree(o)
      is WorktreeNode -> repoOfWorktree[o.path]?.let { return newWorktree(it, o.path.takeIf { _ -> !o.isMain }) }
      is SessionRow -> repoOfWorktree[o.session.worktree]?.let { return newWorktree(it) }
    }
    shown?.repos?.firstOrNull()?.let { newWorktree(it) }
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
          // whole 30 s steps, as the ticker: elapsed times then do not make every refresh a new model
          { state.findWorktree(it)?.userName }, step(System.currentTimeMillis()),
          { state.findWorktree(it)?.parentPath },
        )
      }.getOrNull() ?: return@Runnable
      ApplicationManager.getApplication().invokeLater({ show(built) }, project.disposed)
    })
  }

  /** The ticker: the last model with its elapsed times at [now], on the EDT; no git and no PR lookup. */
  @TestOnly internal fun retime(now: Long) {
    shown?.let { show(it.withElapsed(step(now))) }
  }

  private fun step(now: Long) = now / TICK_MS * TICK_MS

  private fun show(m: SidebarModel) {
    // hook events refresh several times a second while agents work; most change nothing that shows
    if (m == shown) return
    shown = m
    val expanded = (0 until tree.rowCount).mapNotNull { tree.getPathForRow(it) }.filter { tree.isExpanded(it) }.map { key(it) }.toSet()
    val selected = tree.selectionPath?.let { key(it) }
    root.removeAllChildren()
    val toExpand = ArrayList<DefaultMutableTreeNode>()
    val nestedByPath = HashMap<String, NestedWorktree>()
    // the sessions of a worktree, then the worktrees made from it
    fun worktree(parent: DefaultMutableTreeNode, n: NestedWorktree) {
      val w = n.node
      nestedByPath[w.path] = n
      val wn = DefaultMutableTreeNode(w).also(parent::add)
      w.sessions.forEach { wn.add(DefaultMutableTreeNode(it)) }
      n.children.forEach { worktree(wn, it) }
      if (w.sessions.isNotEmpty() || n.children.isNotEmpty()) toExpand += wn
    }
    for (r in m.repos) {
      val nested = SidebarModel.nest(r.worktrees)
      if (m.flat) {
        nested.forEach { worktree(root, it) }
      } else {
        val rn = DefaultMutableTreeNode(r).also(root::add)
        nested.forEach { worktree(rn, it) }
        toExpand += rn
      }
    }
    repoOfWorktree = m.repos.flatMap { r -> r.worktrees.map { it.path to r } }.toMap()
    nestedOf = nestedByPath
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
      PhantomAiBundle.message("worktree.send") to (nestedOf[o.path]?.let { n -> { sendDown(n) } }),
      PhantomAiBundle.message("sidebar.rename") to { renameWorktree(o) },
      PhantomAiBundle.message("worktree.new") to (repoOfWorktree[o.path]?.let { r -> { newWorktree(r, o.path) } }),
      PhantomAiBundle.message("worktree.remove") to (if (o.isMain) null else ({ removeWorktree(o) })),
    )
    is RepoNode -> menu(PhantomAiBundle.message("worktree.new") to { newWorktree(o) })
    is SessionRow -> menu(
      PhantomAiBundle.message("action.resume") to (if (actions.canActivate(o.session)) ({ actions.activate(o.session); Unit }) else null),
      PhantomAiBundle.message("sidebar.rename") to { renameSession(o) },
    )
    else -> null
  }

  private fun activateSelected() {
    val row = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? SessionRow ?: return
    if (actions.canActivate(row.session)) actions.activate(row.session)
  }

  /**
   * One prompt for a worktree and every worktree below it: typed into each one's open agent tabs, and a worktree without
   * one starts Claude on it. The dialog says how many of each before anything is sent.
   */
  private fun sendDown(n: NestedWorktree) {
    val sessions = SessionStore.getInstance()
    val targets = PromptPropagation.targets(n) { w -> sessions.forWorktree(w).map { it.tabId }.filter { actions.terminalOf(it) != null } }
    val typed = targets.count { it is PromptTarget.Type }
    val prompt = Messages.showMultilineInputDialog(
      project, PhantomAiBundle.message("worktree.send.message", n.lineage().size, typed, targets.size - typed),
      PhantomAiBundle.message("worktree.send.title"), "", null, null,
    )?.trim()?.ifEmpty { null } ?: return
    for (t in targets) when (t) {
      is PromptTarget.Type -> actions.terminalOf(t.tabId)?.submit(prompt)
      is PromptTarget.Start -> actions.newSession(t.worktree, AgentKind.CLAUDE, prompt)
    }
  }

  /** Renames the selected worktree or session row (F2, the title bar's Rename); nothing for a repository row. */
  fun renameSelected() {
    when (val o = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject) {
      is WorktreeNode -> renameWorktree(o)
      is SessionRow -> renameSession(o)
    }
  }

  private fun showMenuForSelection() {
    val path = tree.selectionPath ?: return
    val bounds = tree.getPathBounds(path) ?: return
    menuFor(nodeObject(path))?.show(tree, bounds.x, bounds.y + bounds.height)
  }

  private fun renameSession(row: SessionRow) {
    val name = Messages.showInputDialog(project, PhantomAiBundle.message("sidebar.session.name"), PhantomAiBundle.message("sidebar.rename"), null, row.title, null) ?: return
    SessionNames.rename(row.session, name)
    refresh()
  }

  private fun renameWorktree(w: WorktreeNode) {
    val name = Messages.showInputDialog(project, PhantomAiBundle.message("sidebar.worktree.name"), PhantomAiBundle.message("sidebar.rename"), null, w.title, null) ?: return
    AiState.getInstance().worktree(w.path).userName = name.trim().ifEmpty { null }
    AiState.getInstance().changed()
    refresh()
  }

  /**
   * Orca's new workspace: a worktree on a new branch (git off the EDT), then the chosen agent started in it. Made from a
   * worktree row, it shows below [parent], as Orca's lineage; the base branch is chosen apart from that.
   */
  private fun newWorktree(r: RepoNode, parent: String? = null) {
    val repo = r.path
    background(Runnable {
      val base = creator.defaultBase(repo)
      later {
        val request = askNewWorktree(r.name, base) ?: return@later
        background(Runnable {
          val created = creator.create(repo, request.name, request.base)
          later {
            created.onSuccess { w ->
              if (parent != null) {
                AiState.getInstance().worktree(w.path).parentPath = parent
                AiState.getInstance().changed()
              }
              refresh()
              // the agent starts in this window first, so its session stays here while the worktree opens as a project
              request.agent?.let { actions.newSession(w.path, it) }
              if (request.openAsProject) actions.openInPhantom(w.path)
            }.onFailure { Messages.showErrorDialog(project, it.message, PhantomAiBundle.message("worktree.new.failed")) }
          }
        })
      }
    })
  }

  /** Removes a worktree and its branch if merged; changed files need a second, forced confirmation, as in Orca. */
  private fun removeWorktree(w: WorktreeNode) {
    val repo = repoOfWorktree[w.path]?.path ?: return
    WorktreeRemoval(project, creator, background).remove(repo, w.path, w.branch) { refresh() }
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

  /** One row: [dot] title [branch] [#N pill] [agent mark] [elapsed], and + on a hovered repository or worktree row. */
  private class Renderer(private val hovered: () -> Int) : JPanel(FlowLayout(FlowLayout.LEFT, 4, 1)), TreeCellRenderer {
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
    private val plus = JBLabel(com.intellij.icons.AllIcons.General.Add).apply { toolTipText = PhantomAiBundle.message("worktree.new") }

    init {
      listOf(icon, title, hint, pill, chip, elapsed, plus).forEach(::add)
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
      val o0 = (value as? DefaultMutableTreeNode)?.userObject
      plus.isVisible = row == hovered() && (o0 is RepoNode || o0 is WorktreeNode)
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
          chip.icon = dev.phantom.ai.core.AgentIcons.of(o.session.kind)
          chip.toolTipText = o.session.kind.displayName
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

  private companion object {
    /** Width at the end of a row that is its + (icon and gap). */
    const val PLUS_HIT = 22
    const val TICK_MS = 30_000
  }

  private object UIManagerColors {
    fun selectionFg(): Color = javax.swing.UIManager.getColor("Tree.selectionForeground") ?: Color.WHITE
    fun selectionBg(): Color = javax.swing.UIManager.getColor("Tree.selectionBackground") ?: Color.GRAY
  }
}
