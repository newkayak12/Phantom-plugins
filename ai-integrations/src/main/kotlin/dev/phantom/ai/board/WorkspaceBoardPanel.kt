// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.board

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AiState
import dev.phantom.ai.core.AiStateListener
import dev.phantom.ai.core.PhantomAiBundle
import dev.phantom.ai.core.SessionListener
import dev.phantom.ai.core.SessionStore
import dev.phantom.ai.core.WhenShown
import dev.phantom.ai.core.bestEffort
import dev.phantom.ai.memo.MemoChoices
import dev.phantom.ai.sidebar.GitProjectRepos
import dev.phantom.ai.sidebar.PrTitles
import dev.phantom.ai.sidebar.ProjectRepos
import dev.phantom.ai.sidebar.SharedPrTitles
import dev.phantom.ai.sidebar.SharedWorktrees
import dev.phantom.ai.sidebar.SidebarActions
import dev.phantom.ai.sidebar.SidebarModel
import dev.phantom.ai.sidebar.WorktreeSource
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.Point
import java.awt.event.ItemEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities

/**
 * The board: a card per task (worktree, or a planned card without one yet) in the columns Todo / In progress / In review /
 * Done, where moving a card does that step ([BoardFlow]): In progress starts the agent, back from In review sends a
 * follow-up, Done asks first. A finished session moves its card to In review ([BoardReviewMover]). Cards are dragged with
 * the mouse or moved with their buttons; placements are stored in [AiState].
 *
 * With [allProjects] the board covers the repositories of every open project and gets a project filter: the Agent
 * Dashboard window. Without it, it is the Board tab of [project].
 */
class WorkspaceBoardPanel(
  private val project: Project,
  private val worktrees: WorktreeSource = SharedWorktrees,
  private val prTitles: PrTitles = SharedPrTitles,
  private val repos: ProjectRepos = GitProjectRepos(project),
  private val allProjects: (() -> List<Project>)? = null,
  private val reposOf: (Project) -> ProjectRepos = { GitProjectRepos(it) },
  private val dialogs: BoardDialogs = IdeBoardDialogs(project),
  private val work: BoardWork = BoardWork(),
) : JPanel(BorderLayout()), Disposable {
  private val columnsPanel = JPanel(GridLayout(1, 0, 8, 0))
  private val projectFilter = JComboBox<Any>().apply { name = "board.project" }
  private val columnPanels = LinkedHashMap<String, JPanel>()
  private val cardViews = LinkedHashMap<String, JComponent>()
  private val refreshQueued = AtomicBoolean()
  /** Every card of the scope; [cards] are the ones the project filter shows. */
  private var allCards: List<BoardCard> = emptyList()
  private var cards: Map<String, BoardCard> = emptyMap()
  @Volatile private var projects: Map<String, Project> = emptyMap()
  @Volatile private var repoRoots: List<String> = emptyList()
  private var boardLayout: BoardLayout = BoardLayout.of(AiState.getInstance().boardColumns(), emptyList()) { null }
  private var updatingFilter = false
  private val whenShown = WhenShown(this, ::refresh)
  private var rendered = false

  init {
    val top = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
    top.add(ActionLink(PhantomAiBundle.message("board.new.card")) { newCard() }.apply { name = "board.new" })
    if (allProjects != null) top.add(projectFilter)
    projectFilter.addItemListener { if (it.stateChange == ItemEvent.SELECTED && !updatingFilter) showCards(allCards) }
    add(top, BorderLayout.NORTH)
    columnsPanel.border = JBUI.Borders.empty(8)
    add(ScrollPaneFactory.createScrollPane(columnsPanel), BorderLayout.CENTER)
    ApplicationManager.getApplication().messageBus.connect(this)
      .subscribe(SessionStore.TOPIC, SessionListener { _, _ -> whenShown.request() })
    ApplicationManager.getApplication().messageBus.connect(this).subscribe(AiState.TOPIC, AiStateListener { whenShown.request() })
    whenShown.request()
  }

  override fun dispose() = Unit

  /** The current boardLayout (for tests and the drag). */
  fun currentLayout(): BoardLayout = boardLayout

  fun card(path: String): BoardCard? = cards[path]

  /** Picks the project filter by project base path; null shows all (tests and callers). */
  fun filterProject(path: String?) {
    val i = (0 until projectFilter.itemCount).firstOrNull { (projectFilter.getItemAt(it) as? ProjectItem)?.path == path } ?: 0
    if (projectFilter.itemCount > 0) projectFilter.selectedIndex = i
    showCards(allCards)
  }

  /** Builds the cards: git and the PR lookup run here, so call off the EDT. */
  fun buildCards(): List<BoardCard> {
    val state = AiState.getInstance()
    val scope = allProjects?.invoke()?.filter { !it.isDisposed } ?: listOf(project)
    val roots = HashMap<String, Project>()
    val built = scope.flatMap { p ->
      val pRoots = (if (allProjects == null) repos else reposOf(p)).roots()
      pRoots.forEach { roots.putIfAbsent(it, p) }
      val model = SidebarModel.build(
        pRoots, worktrees, SessionStore.getInstance().all(), emptyList(), prTitles,
        { state.findWorktree(it)?.userName }, System.currentTimeMillis(),
      )
      model.repos.flatMap { r ->
        r.worktrees.map { w ->
          val entry = state.findWorktree(w.path)
          BoardCard(
            w.path, w.title, w.branch, w.prNumber,
            w.sessions.map { BoardSession(it.session.tabId, statusKey(it.session)) },
            r.path, p.basePath, p.name, entry?.prompt, agentOf(entry?.agent), w.prUrl, w.isMain,
          )
        }
      }
    }.distinctBy { it.path }
    val planned = state.state.worktrees.filter { BoardFlow.isPlanned(it.path.orEmpty()) && it.repo in roots }.map { e ->
      val p = roots.getValue(e.repo!!)
      BoardCard(e.path!!, e.userName ?: e.prompt.orEmpty(), null, null, emptyList(), e.repo, p.basePath, p.name, e.prompt, agentOf(e.agent))
    }
    projects = roots.values.associateBy { it.basePath.orEmpty() }
    repoRoots = roots.keys.sorted()
    return planned + built
  }

  fun refresh() {
    if (!refreshQueued.compareAndSet(false, true)) return
    ApplicationManager.getApplication().executeOnPooledThread {
      refreshQueued.set(false)
      val built = bestEffort { buildCards() }.getOrNull() ?: return@executeOnPooledThread
      ApplicationManager.getApplication().invokeLater({ showCards(built) }, project.disposed)
    }
  }

  /** Lays [list] (filtered by project) out in the stored columns and order (EDT). */
  fun showCards(list: List<BoardCard>) {
    allCards = list
    updateFilter(list)
    val only = (projectFilter.selectedItem as? ProjectItem)?.path
    val shown = list.filter { only == null || it.projectPath == only }
    val state = AiState.getInstance()
    val nextCards = shown.associateBy { it.path }
    val next = BoardLayout.of(state.boardColumns(), shown.map { it.path }) { path ->
      state.findWorktree(path)?.let { Placement(it.column, it.order) }
    }
    // most events change nothing on the board; it is rebuilt only when a card or its place did
    if (rendered && nextCards == cards && next.columns == boardLayout.columns && next.placements() == boardLayout.placements()) return
    cards = nextCards
    boardLayout = next
    render()
  }

  private fun updateFilter(list: List<BoardCard>) {
    if (allProjects == null) return
    val items = list.mapNotNull { c -> c.projectPath?.let { ProjectItem(c.projectName ?: it, it) } }.distinctBy { it.path }
    val now = (0 until projectFilter.itemCount).mapNotNull { projectFilter.getItemAt(it) as? ProjectItem }
    if (projectFilter.itemCount > 0 && items == now) return
    val keep = (projectFilter.selectedItem as? ProjectItem)?.path
    updatingFilter = true
    projectFilter.removeAllItems()
    projectFilter.addItem(PhantomAiBundle.message("board.project.all"))
    items.forEach { projectFilter.addItem(it) }
    (0 until projectFilter.itemCount).firstOrNull { (projectFilter.getItemAt(it) as? ProjectItem)?.path == keep }
      ?.let { projectFilter.selectedIndex = it }
    updatingFilter = false
  }

  /**
   * Moves a card (the drag, the card buttons and tests call this) and does the step of that move; a question the user
   * cancels leaves the card where it was.
   */
  fun moveCard(path: String, column: String, index: Int) {
    val card = cards[path] ?: return
    val target = column.takeIf { it in boardLayout.columns } ?: boardLayout.columns.first()
    val owner = projectOf(card)
    when (BoardFlow.step(boardLayout.columnOf(path), target, card.planned)) {
      BoardStep.REJECT -> return
      BoardStep.NONE -> place(path, target, index)
      BoardStep.START -> {
        // a card with a prompt starts at once; one without (a worktree made elsewhere) asks for it
        val prompt = card.prompt ?: (dialogs.prompt(card, followUp = false) ?: return)
        place(path, target, index)
        work.start(owner, card, prompt.ifBlank { null })
      }
      BoardStep.FOLLOW_UP -> {
        val prompt = dialogs.prompt(card, followUp = true)?.takeIf { it.isNotBlank() } ?: return
        place(path, target, index)
        work.followUp(owner, card, prompt)
      }
      BoardStep.FINISH -> {
        val choice = dialogs.confirmDone(card) ?: return
        place(path, target, index)
        work.finish(owner, card, choice.removeWorktree)
      }
    }
  }

  private fun place(path: String, column: String, index: Int) {
    val next = boardLayout.move(path, column, index)
    boardLayout = next
    AiState.getInstance().placeCards(next.placements())
    render()
  }

  /** New Card: a planned card in Todo of one of the board's repositories. */
  fun newCard() {
    val draft = dialogs.editCard(repoRoots, null) ?: return
    if (draft.repo.isEmpty()) return
    AiState.getInstance().newCard(draft.repo, draft.title, draft.prompt, draft.agent)
    AiState.getInstance().changed()
    refresh()
  }

  /** Edit Card: title (the worktree's name), prompt and agent. */
  fun editCard(card: BoardCard) {
    val draft = dialogs.editCard(repoRoots, card) ?: return
    val state = AiState.getInstance()
    state.worktree(card.path).apply { userName = draft.title; prompt = draft.prompt; agent = draft.agent.name }
    state.changed()
    refresh()
  }

  /** Focuses the session's terminal (in whichever window has it; a detached one reattaches here) and clears unread. */
  fun jumpTo(card: BoardCard, tabId: String) {
    val session = SessionStore.getInstance().get(tabId) ?: return
    SidebarActions(projectOf(card)).activate(session)
    SessionStore.getInstance().update(tabId) { it.copy(unread = false) }
  }

  private fun projectOf(card: BoardCard): Project = card.projectPath?.let { projects[it] }?.takeIf { !it.isDisposed } ?: project

  private fun render() {
    rendered = true
    columnsPanel.removeAll()
    columnPanels.clear()
    cardViews.clear()
    if (cards.isEmpty()) columnsPanel.layout = BorderLayout().also { columnsPanel.add(JBLabel(PhantomAiBundle.message("board.empty")), BorderLayout.NORTH) }
    else columnsPanel.layout = GridLayout(1, 0, 8, 0)
    if (cards.isNotEmpty()) for (name in boardLayout.columns) {
      val body = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false }
      boardLayout.cardsOf(name).mapNotNull { cards[it] }.forEach { c ->
        val view = cardView(c, name)
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

  private fun cardView(c: BoardCard, column: String): JComponent {
    val p = JPanel(BorderLayout()).apply {
      name = "card:${c.path}"
      border = BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(JBColor.border()), JBUI.Borders.empty(6))
      alignmentX = Component.LEFT_ALIGNMENT
    }
    val rows = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false }
    val top = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT }
    top.add(JBLabel(c.title).apply { font = font.deriveFont(java.awt.Font.BOLD) })
    c.prNumber?.let { n ->
      if (c.prUrl != null) top.add(ActionLink("#$n") { BrowserUtil.browse(c.prUrl) }.apply { name = "pr" })
      else top.add(JBLabel("#$n").apply { foreground = JBColor.BLUE; name = "pr" })
    }
    if (allProjects != null) c.projectName?.let { top.add(JBLabel(it).apply { foreground = JBColor.GRAY; name = "project" }) }
    rows.add(top)
    val sub = if (c.planned) PhantomAiBundle.message("board.planned", c.repo.orEmpty().substringAfterLast('/')) else c.branch.orEmpty()
    rows.add(line(sub, "branch"))
    c.prompt?.takeIf { it.isNotBlank() }?.let { rows.add(line("> " + oneLine(it), "prompt")) }
    if (c.sessions.isNotEmpty()) {
      val badges = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply { isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT }
      c.sessions.forEach { s -> badges.add(badge(c, s)) }
      rows.add(badges)
    }
    val links = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply { isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT }
    fun link(key: String, run: () -> Unit) = links.add(ActionLink(PhantomAiBundle.message(key)) { run() }.apply { name = "action:$key" })
    val end = { col: String -> boardLayout.cardsOf(col).size }
    when (column) {
      BoardFlow.TODO -> link("board.start") { moveCard(c.path, BoardFlow.IN_PROGRESS, end(BoardFlow.IN_PROGRESS)) }
      BoardFlow.IN_PROGRESS -> if (!c.planned) link("board.changes") { work.showChanges(projectOf(c), c) }
      BoardFlow.IN_REVIEW -> {
        link("board.changes") { work.showChanges(projectOf(c), c) }
        link("board.followup") { moveCard(c.path, BoardFlow.IN_PROGRESS, end(BoardFlow.IN_PROGRESS)) }
        link("board.done") { moveCard(c.path, BoardFlow.DONE, end(BoardFlow.DONE)) }
      }
    }
    if (links.componentCount > 0) rows.add(links)
    p.add(rows, BorderLayout.CENTER)
    p.maximumSize = java.awt.Dimension(Int.MAX_VALUE, p.preferredSize.height)
    val drag = DragHandler(c.path)
    p.addMouseListener(drag)
    p.addMouseMotionListener(drag)
    p.componentPopupMenu = JPopupMenu().apply {
      add(JMenuItem(PhantomAiBundle.message("board.edit")).apply { addActionListener { editCard(c) } })
    }
    return p
  }

  /** A session's status; "waiting for you" stands out. A click jumps to its terminal. */
  private fun badge(c: BoardCard, s: BoardSession): JComponent = JBLabel(PhantomAiBundle.message("board.status.${s.status}")).apply {
    name = "badge:${s.status}:${s.tabId}"
    foreground = if (s.status == "waiting") JBColor.WHITE else statusColor(s.status)
    if (s.status == "waiting") {
      isOpaque = true
      background = statusColor(s.status)
      font = font.deriveFont(java.awt.Font.BOLD)
    }
    border = JBUI.Borders.empty(0, 4)
    cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
    addMouseListener(object : MouseAdapter() {
      override fun mouseClicked(e: MouseEvent) = jumpTo(c, s.tabId)
    })
  }

  private fun line(text: String, name: String) =
    JBLabel(text).apply { this.name = name; foreground = JBColor.GRAY; alignmentX = Component.LEFT_ALIGNMENT }

  private fun oneLine(s: String): String = s.trim().lineSequence().first().let { if (it.length > MAX_LINE) it.take(MAX_LINE) + "…" else it }

  private fun statusColor(status: String): Color = when (status) {
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

  private class ProjectItem(val name: String, val path: String) {
    override fun toString() = name
    override fun equals(other: Any?) = other is ProjectItem && other.path == path && other.name == name
    override fun hashCode() = path.hashCode()
  }

  private companion object {
    const val DRAG_THRESHOLD = 6.0
    const val MAX_LINE = 120

    /** working | waiting | done | launched; Claude's idle prompt is a finished turn, not a question. */
    fun statusKey(s: dev.phantom.ai.core.AgentSession): String =
      MemoChoices.effectiveStatus(s)?.name?.lowercase() ?: "launched"

    fun agentOf(name: String?): AgentKind = AgentKind.entries.firstOrNull { it.name == name } ?: AgentKind.CLAUDE
  }
}
