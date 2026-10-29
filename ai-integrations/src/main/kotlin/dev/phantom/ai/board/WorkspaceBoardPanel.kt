// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.board

import dev.phantom.ai.core.bestEffort
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.phantom.ai.core.AiState
import dev.phantom.ai.core.PhantomAiBundle
import dev.phantom.ai.core.SessionListener
import dev.phantom.ai.core.SessionStore
import dev.phantom.ai.sidebar.CachedPrTitles
import dev.phantom.ai.sidebar.SharedWorktrees
import dev.phantom.ai.sidebar.GithubPrTitles
import dev.phantom.ai.sidebar.GitProjectRepos
import dev.phantom.ai.sidebar.ProjectRepos
import dev.phantom.ai.sidebar.PrTitles
import dev.phantom.ai.sidebar.SidebarModel
import dev.phantom.ai.sidebar.WorktreeSource
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** What a card shows: title (user name > PR title > branch), branch, PR number and the status keys of its sessions. */
data class BoardCard(val path: String, val title: String, val branch: String?, val prNumber: Int?, val statuses: List<String>)

/**
 * The "Board" tab: a worktree card per worktree of this project's repositories, in status columns. A card is
 * dragged with the mouse; the move goes through [BoardLayout] and is stored in [AiState].
 */
class WorkspaceBoardPanel(
  private val project: Project,
  private val worktrees: WorktreeSource = SharedWorktrees,
  private val prTitles: PrTitles = CachedPrTitles(GithubPrTitles()),
  private val repos: ProjectRepos = GitProjectRepos(project),
) : JPanel(BorderLayout()), Disposable {
  private val columnsPanel = JPanel(GridLayout(1, 0, 8, 0))
  private val columnPanels = LinkedHashMap<String, JPanel>()
  private val cardViews = LinkedHashMap<String, JComponent>()
  private val refreshQueued = AtomicBoolean()
  private var cards: Map<String, BoardCard> = emptyMap()
  private var boardLayout: BoardLayout = BoardLayout.of(AiState.getInstance().boardColumns(), emptyList()) { null }

  init {
    columnsPanel.border = JBUI.Borders.empty(8)
    add(ScrollPaneFactory.createScrollPane(columnsPanel), BorderLayout.CENTER)
    ApplicationManager.getApplication().messageBus.connect(this)
      .subscribe(SessionStore.TOPIC, SessionListener { _, _ -> refresh() })
    ApplicationManager.getApplication().messageBus.connect(this).subscribe(AiState.TOPIC, dev.phantom.ai.core.AiStateListener { refresh() })
    refresh()
  }

  override fun dispose() = Unit

  /** The current boardLayout (for tests and the drag). */
  fun currentLayout(): BoardLayout = boardLayout

  /** Builds the cards: git and the PR lookup run here, so call off the EDT. */
  fun buildCards(): List<BoardCard> {
    val state = AiState.getInstance()
    val model = SidebarModel.build(
      repos.roots(), worktrees, SessionStore.getInstance().all(),
      state.state.sessions.mapNotNull(SidebarModel::saved), prTitles,
      { state.findWorktree(it)?.userName }, System.currentTimeMillis(),
    )
    return model.repos.flatMap { it.worktrees }.distinctBy { it.path }.map { w ->
      BoardCard(w.path, w.title, w.branch, w.prNumber, w.sessions.map { it.statusKey })
    }
  }

  fun refresh() {
    if (!refreshQueued.compareAndSet(false, true)) return
    ApplicationManager.getApplication().executeOnPooledThread {
      refreshQueued.set(false)
      val built = bestEffort { buildCards() }.getOrNull() ?: return@executeOnPooledThread
      ApplicationManager.getApplication().invokeLater({ showCards(built) }, project.disposed)
    }
  }

  /** Lays [list] out in the stored columns and order (EDT). */
  fun showCards(list: List<BoardCard>) {
    val state = AiState.getInstance()
    cards = list.associateBy { it.path }
    boardLayout = BoardLayout.of(state.boardColumns(), list.map { it.path }) { path ->
      state.findWorktree(path)?.let { Placement(it.column, it.order) }
    }
    render()
  }

  /** Moves a card (the drag calls this; tests too) and stores the result. */
  fun moveCard(path: String, column: String, index: Int) {
    val next = boardLayout.move(path, column, index)
    boardLayout = next
    AiState.getInstance().placeCards(next.placements())
    render()
  }

  private fun render() {
    columnsPanel.removeAll()
    columnPanels.clear()
    cardViews.clear()
    if (cards.isEmpty()) columnsPanel.layout = BorderLayout().also { columnsPanel.add(JBLabel(PhantomAiBundle.message("board.empty")), BorderLayout.NORTH) }
    else columnsPanel.layout = GridLayout(1, 0, 8, 0)
    if (cards.isNotEmpty()) for (name in boardLayout.columns) {
      val body = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false }
      boardLayout.cardsOf(name).mapNotNull { cards[it] }.forEach { c ->
        val view = cardView(c)
        cardViews[c.path] = view
        body.add(view)
        body.add(javax.swing.Box.createVerticalStrut(6))
      }
      val col = JPanel(BorderLayout()).apply {
        border = BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(JBColor.border()), JBUI.Borders.empty(6))
        add(JBLabel("$name (${boardLayout.cardsOf(name).size})").apply { border = JBUI.Borders.emptyBottom(6) }, BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)
      }
      columnPanels[name] = body
      columnsPanel.add(col)
    }
    columnsPanel.revalidate()
    columnsPanel.repaint()
  }

  private fun cardView(c: BoardCard): JComponent {
    val p = JPanel(BorderLayout()).apply {
      name = "card:${c.path}"
      border = BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(JBColor.border()), JBUI.Borders.empty(6))
      maximumSize = java.awt.Dimension(Int.MAX_VALUE, preferredSize.height.coerceAtLeast(60))
    }
    val top = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { isOpaque = false }
    top.add(JBLabel(c.title))
    c.prNumber?.let { top.add(JBLabel("#$it").apply { foreground = JBColor.BLUE }) }
    p.add(top, BorderLayout.NORTH)
    p.add(JBLabel(c.branch.orEmpty()).apply { foreground = JBColor.GRAY }, BorderLayout.CENTER)
    val dots = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply { isOpaque = false }
    c.statuses.forEach { s -> dots.add(JBLabel("●").apply { foreground = dotColor(s); toolTipText = s; name = "dot:$s" }) }
    p.add(dots, BorderLayout.SOUTH)
    val drag = DragHandler(c.path)
    p.addMouseListener(drag)
    p.addMouseMotionListener(drag)
    return p
  }

  private fun dotColor(status: String): Color = when (status) {
    "working" -> JBColor.ORANGE
    "waiting" -> JBColor.RED
    "done" -> JBColor.GREEN
    else -> JBColor.GRAY
  }

  /** Press on a card, release over a column: the drop column and index come from where the pointer is. */
  private inner class DragHandler(private val path: String) : MouseAdapter() {
    private var pressed: Point? = null

    override fun mousePressed(e: MouseEvent) {
      pressed = SwingUtilities.convertPoint(e.component, e.point, columnsPanel)
    }

    override fun mouseReleased(e: MouseEvent) {
      val from = pressed ?: return
      pressed = null
      val pt = SwingUtilities.convertPoint(e.component, e.point, columnsPanel)
      if (from.distance(pt) < DRAG_THRESHOLD) return
      val (name, body) = columnPanels.entries.firstOrNull { (_, b) -> columnBounds(b).contains(pt) } ?: return
      val y = SwingUtilities.convertPoint(columnsPanel, pt, body).y
      val others = boardLayout.cardsOf(name).filter { it != path }
      val index = others.count { other -> cardViews[other]?.let { it.y + it.height / 2 < y } == true }
      moveCard(path, name, index)
    }

    private fun columnBounds(body: Component) =
      SwingUtilities.convertRectangle(body.parent, java.awt.Rectangle(0, 0, body.parent.width, body.parent.height), columnsPanel)
  }

  private companion object {
    const val DRAG_THRESHOLD = 6.0
  }
}
