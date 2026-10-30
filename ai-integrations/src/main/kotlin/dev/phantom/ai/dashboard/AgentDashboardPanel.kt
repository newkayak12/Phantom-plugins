// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.dashboard

import dev.phantom.ai.core.bestEffort
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentStatus
import dev.phantom.ai.core.AiState
import dev.phantom.ai.core.PhantomAiBundle
import dev.phantom.ai.core.SessionListener
import dev.phantom.ai.core.SessionStore
import dev.phantom.ai.sidebar.SharedPrTitles
import dev.phantom.ai.sidebar.SharedWorktrees
import dev.phantom.ai.sidebar.GitProjectRepos
import dev.phantom.ai.sidebar.ProjectRepos
import dev.phantom.ai.sidebar.PrTitles
import dev.phantom.ai.sidebar.SidebarModel
import dev.phantom.ai.sidebar.WorktreeSource
import dev.phantom.ai.terminal.AgentTerminalService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.GridLayout
import java.awt.RenderingHints
import java.awt.event.ItemEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * The content of the Agent Dashboard window: total count, search and filters on top, then the columns Needs attention /
 * Working / Done. A card click focuses that session's terminal tab (which clears unread). Data comes from one
 * [SidebarModel] per open project (the sidebar's model); git and PR lookups run off the EDT.
 */
class AgentDashboardPanel(
  private val project: Project,
  private val worktrees: WorktreeSource = SharedWorktrees,
  private val prTitles: PrTitles = SharedPrTitles,
  /** The repositories of an open project; one model is built per open project. */
  private val reposOf: (Project) -> ProjectRepos = { GitProjectRepos(it) },
) : JPanel(BorderLayout()), Disposable {
  private val totalLabel = JBLabel().apply { name = "dashboard.total" }
  private val search = SearchTextField(false).apply { textEditor.emptyText.text = PhantomAiBundle.message("dashboard.search") }
  private val agentFilter = JComboBox<Any>().apply { name = "dashboard.agent" }
  private val projectFilter = JComboBox<Any>().apply { name = "dashboard.project" }
  private val columnsPanel = JPanel(GridLayout(1, 3, 8, 0))
  private val refreshQueued = AtomicBoolean()
  private val expanded = HashSet<String>()
  @Volatile private var sidebar: List<SidebarModel>? = null
  private var model: DashboardModel = DashboardModel(emptyList(), emptyList())
  private var updatingFilters = false
  private var rendered = false
  private val whenShown = dev.phantom.ai.core.WhenShown(this, ::refresh)

  init {
    val top = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
    top.add(totalLabel)
    top.add(search)
    top.add(agentFilter)
    top.add(projectFilter)
    add(top, BorderLayout.NORTH)
    columnsPanel.border = JBUI.Borders.empty(8)
    add(ScrollPaneFactory.createScrollPane(columnsPanel), BorderLayout.CENTER)
    agentFilter.addItem(PhantomAiBundle.message("dashboard.filter.agent"))
    AgentKind.entries.forEach { agentFilter.addItem(it) }
    agentFilter.addItemListener { if (it.stateChange == ItemEvent.SELECTED) rebuild() }
    projectFilter.addItemListener { if (it.stateChange == ItemEvent.SELECTED && !updatingFilters) rebuild() }
    // the cards are rebuilt once typing pauses, not per key
    val typed = javax.swing.Timer(150) { rebuild() }.apply { isRepeats = false }
    search.textEditor.document.addDocumentListener(object : DocumentListener {
      override fun insertUpdate(e: DocumentEvent) = typed.restart()
      override fun removeUpdate(e: DocumentEvent) = typed.restart()
      override fun changedUpdate(e: DocumentEvent) = typed.restart()
    })
    ApplicationManager.getApplication().messageBus.connect(this)
      .subscribe(SessionStore.TOPIC, SessionListener { _, _ -> whenShown.request() })
    ApplicationManager.getApplication().messageBus.connect(this).subscribe(AiState.TOPIC, dev.phantom.ai.core.AiStateListener { whenShown.request() })
    whenShown.request()
  }

  override fun dispose() = Unit

  fun currentModel(): DashboardModel = model

  /** Sets the search text (tests and callers). */
  fun setSearch(text: String) {
    search.text = text
    rebuild()
  }

  /** Builds one sidebar model per open project: git and the PR lookup run here, so call off the EDT. */
  fun buildSidebar(): List<SidebarModel> {
    val state = AiState.getInstance()
    val open = ProjectManager.getInstance().openProjects.filter { !it.isDisposed }
    return open.map { p ->
      SidebarModel.build(
        reposOf(p).roots(), worktrees, SessionStore.getInstance().all(),
        state.state.sessions.mapNotNull(SidebarModel::saved), prTitles,
        // whole 30 s steps: elapsed times then do not make every refresh a new model
        { state.findWorktree(it)?.userName }, System.currentTimeMillis() / 30_000 * 30_000,
      )
    }
  }

  fun refresh() {
    if (!refreshQueued.compareAndSet(false, true)) return
    ApplicationManager.getApplication().executeOnPooledThread {
      refreshQueued.set(false)
      val built = bestEffort { buildSidebar() }.getOrNull() ?: return@executeOnPooledThread
      ApplicationManager.getApplication().invokeLater({ show(built) }, project.disposed)
    }
  }

  /** Shows [built] under the current search and filters (EDT). */
  fun show(built: List<SidebarModel>) {
    if (built == sidebar) return
    sidebar = built
    val keep = (projectFilter.selectedItem as? ProjectItem)?.path
    val projects = DashboardModel.of(built).projects
    updatingFilters = true
    projectFilter.removeAllItems()
    projectFilter.addItem(PhantomAiBundle.message("dashboard.filter.project"))
    projects.forEach { projectFilter.addItem(ProjectItem(it.first, it.second)) }
    (0 until projectFilter.itemCount).firstOrNull { (projectFilter.getItemAt(it) as? ProjectItem)?.path == keep }
      ?.let { projectFilter.selectedIndex = it }
    updatingFilters = false
    rebuild()
  }

  private fun rebuild() {
    val sb = sidebar ?: return
    val query = DashboardQuery(
      search.text.orEmpty(),
      agentFilter.selectedItem as? AgentKind,
      (projectFilter.selectedItem as? ProjectItem)?.path,
    )
    val next = DashboardModel.of(sb, query)
    if (next == model && rendered) return
    model = next
    render()
  }

  private fun render() {
    rendered = true
    totalLabel.text = PhantomAiBundle.message("dashboard.total", model.total)
    columnsPanel.removeAll()
    model.columns.forEach { col -> columnsPanel.add(columnView(col)) }
    columnsPanel.revalidate()
    columnsPanel.repaint()
  }

  private fun columnView(col: DashboardColumn): JComponent {
    val key = col.status.name.lowercase()
    val body = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false; name = "column:$key" }
    col.emptyText?.let { body.add(JBLabel(it).apply { foreground = JBColor.GRAY; name = "empty:$key" }) }
    col.cards.forEach { body.add(cardView(it)); body.add(Box.createVerticalStrut(6)) }
    return JPanel(BorderLayout()).apply {
      border = BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(JBColor.border()), JBUI.Borders.empty(6))
      add(
        JBLabel("${PhantomAiBundle.message("dashboard.column.$key")} (${col.count})").apply {
          name = "header:$key"
          border = JBUI.Borders.emptyBottom(6)
          icon = DotIcon(dotColor(col.status))
        },
        BorderLayout.NORTH,
      )
      add(body, BorderLayout.CENTER)
    }
  }

  private fun cardView(c: DashboardCard): JComponent {
    val p = JPanel(BorderLayout(0, 2)).apply {
      name = "card:${c.tabId}"
      border = BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(JBColor.border()), JBUI.Borders.empty(6))
      alignmentX = Component.LEFT_ALIGNMENT
    }
    val top = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { isOpaque = false }
    top.add(JBLabel(AgentIcon(c.kind)).apply { name = "agent:${c.kind.name.lowercase()}"; toolTipText = c.kind.displayName })
    top.add(JBLabel("●").apply { foreground = dotColor(c.status); name = "dot:${c.status.name.lowercase()}" })
    top.add(JBLabel(c.title).apply { font = font.deriveFont(java.awt.Font.BOLD) })
    if (c.unread) top.add(JBLabel("•").apply { foreground = JBColor.BLUE; name = "unread" })
    val rows = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false }
    rows.add(top)
    c.lastPrompt?.takeIf { it.isNotBlank() }?.let { rows.add(line("prompt", "> ${oneLine(it)}")) }
    c.lastAnswer?.takeIf { it.isNotBlank() }?.let { rows.add(line("answer", oneLine(it))) }
    if (c.subagents.isNotEmpty()) rows.add(subagentsView(c))
    p.add(rows, BorderLayout.CENTER)
    val foot = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { isOpaque = false }
    foot.add(JBLabel(c.project, AllIcons.Nodes.Folder, JBLabel.LEFT).apply { name = "project" })
    c.branch?.let { foot.add(JBLabel(it, AllIcons.Vcs.Branch, JBLabel.LEFT).apply { name = "branch"; foreground = JBColor.GRAY }) }
    c.prNumber?.let { foot.add(JBLabel("#$it").apply { foreground = JBColor.BLUE; name = "pr" }) }
    c.elapsed?.let { foot.add(JBLabel(it).apply { foreground = JBColor.GRAY; name = "elapsed" }) }
    p.add(foot, BorderLayout.SOUTH)
    p.maximumSize = java.awt.Dimension(Int.MAX_VALUE, p.preferredSize.height)
    p.addMouseListener(object : MouseAdapter() {
      override fun mouseClicked(e: MouseEvent) = open(c.tabId)
    })
    return p
  }

  /** What a card click does: focus the session's terminal (clears unread), and clear unread even if the tab is gone. */
  fun open(tabId: String) {
    AgentTerminalService.getInstance(project).focus(tabId)
    SessionStore.getInstance().update(tabId) { it.copy(unread = false) }
  }

  private fun subagentsView(c: DashboardCard): JComponent {
    val box = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT }
    val open = c.tabId in expanded
    val header = JBLabel((if (open) "▾ " else "▸ ") + PhantomAiBundle.message("dashboard.subagents", c.subagents.size)).apply {
      name = "subagents:${c.tabId}"
      foreground = JBColor.GRAY
      cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
      addMouseListener(object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) {
          e.consume()
          toggle(c.tabId)
        }
      })
    }
    box.add(header)
    if (open) c.subagents.forEach { sub ->
      box.add(JBLabel("   ${if (sub.running) "●" else "✓"} ${sub.name}").apply { name = "subagent:${sub.id}"; foreground = JBColor.GRAY })
    }
    return box
  }

  /** Expands or collapses the subagent list of a card. */
  fun toggle(tabId: String) {
    if (!expanded.add(tabId)) expanded.remove(tabId)
    render()
  }

  private fun line(name: String, text: String) = JBLabel(text).apply { this.name = name; foreground = JBColor.GRAY }

  private fun oneLine(s: String): String = s.trim().lineSequence().first().let { if (it.length > MAX_LINE) it.take(MAX_LINE) + "…" else it }

  private fun dotColor(status: AgentStatus): Color = when (status) {
    AgentStatus.WORKING -> JBColor.ORANGE
    AgentStatus.WAITING -> JBColor.RED
    AgentStatus.DONE -> JBColor.GREEN
  }

  private class ProjectItem(val name: String, val path: String) {
    override fun toString() = name
  }

  private class DotIcon(private val color: Color) : Icon {
    override fun getIconWidth() = 10
    override fun getIconHeight() = 10
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
      (g as? java.awt.Graphics2D)?.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g.color = color
      g.fillOval(x, y, 9, 9)
    }
  }

  /** Round badge with the agent's initial: C for Claude, X for Codex. */
  private class AgentIcon(private val kind: AgentKind) : Icon {
    override fun getIconWidth() = 16
    override fun getIconHeight() = 16
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
      (g as? java.awt.Graphics2D)?.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g.color = if (kind == AgentKind.CLAUDE) Color(0xD97757) else Color(0x10A37F)
      g.fillOval(x, y, 15, 15)
      g.color = Color.WHITE
      g.drawString(if (kind == AgentKind.CLAUDE) "C" else "X", x + 4, y + 12)
    }
  }

  private companion object {
    const val MAX_LINE = 140
  }
}
