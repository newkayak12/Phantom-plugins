// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ToolWindowType
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.InplaceButton
import com.intellij.ui.OnePixelSplitter
import com.intellij.util.ui.JBUI
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.PhantomAiBundle
import dev.phantom.ai.sidebar.WorktreeTreePanel
import dev.phantom.ai.terminal.AgentTabsPanel
import dev.phantom.ai.terminal.AgentTerminalService
import org.jetbrains.annotations.TestOnly
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * The one panel of the tool window (mockups B and D): the worktree tree column and the agent terminal tabs, side by side
 * when docked; floating or windowed, side by side when wide and stacked when narrow. Docked, + Claude / + Codex head the tree column and Board / Dashboard end it; floating,
 * windowed, or with the tree hidden, one full-width row above the split holds all four. The tree folds into a thin
 * "Worktrees" strip (its « button or the title bar toggle) and a click on the strip unfolds it.
 */
class AgentToolWindowPanel(
  private val project: Project,
  parent: Disposable,
  private val typeOf: () -> ToolWindowType = {
    ToolWindowManager.getInstance(project).getToolWindow(AgentTerminalService.TOOL_WINDOW_ID)?.type ?: ToolWindowType.DOCKED
  },
  private val props: PropertiesComponent = PropertiesComponent.getInstance(),
  val tree: WorktreeTreePanel = WorktreeTreePanel(project).also { Disposer.register(parent, it) },
  val tabs: AgentTabsPanel = AgentTabsPanel(project, parent),
  private val launch: (String, AgentKind) -> Unit = { path, kind -> AgentTerminalService.getInstance(project).launch(path, kind) },
  private val openAction: (String) -> Unit = { id -> runAction(project, id) },
) : JPanel(BorderLayout()) {
  private val fold = TreeFold(hidden = props.getBoolean(SplitLayout.KEY_TREE_HIDDEN, false))
  private val topRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply { name = "toolwindow.topRow" }
  private val treeHeader = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply { name = "toolwindow.treeHeader" }
  private val treeFooter = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply { name = "toolwindow.treeFooter" }
  private val treeColumn = JPanel(BorderLayout()).apply { name = "toolwindow.treeColumn" }
  private val foldButton = InplaceButton(PhantomAiBundle.message("toolwindow.hide.tree"), AllIcons.General.ChevronLeft) { toggleTree() }
    .apply { name = "btn.foldTree" }
  private val treeTitle = JPanel(BorderLayout()).apply {
    name = "toolwindow.treeTitle"
    border = JBUI.Borders.empty(4, 8, 0, 6)
    add(JLabel(PhantomAiBundle.message("toolwindow.tree.title")), BorderLayout.CENTER)
    add(foldButton, BorderLayout.EAST)
  }
  private val strip = FoldStrip(PhantomAiBundle.message("toolwindow.tree.title"), PhantomAiBundle.message("toolwindow.show.tree")) { toggleTree() }
  private val center = JPanel(BorderLayout())
  private var splitter: OnePixelSplitter? = null
  private var orientation: SplitOrientation? = null
  private var laidOut: Triple<Boolean, Boolean, SplitOrientation>? = null

  private val claudeButton = button("toolwindow.new.claude", "btn.claude") { newSession(AgentKind.CLAUDE) }.apply {
    icon = dev.phantom.ai.core.AgentIcons.CLAUDE
    putClientProperty("JButton.buttonType", "default")
    putClientProperty("DefaultButton", true)
  }
  private val codexButton = button("toolwindow.new.codex", "btn.codex") { newSession(AgentKind.CODEX) }.apply { icon = dev.phantom.ai.core.AgentIcons.CODEX }
  private val boardButton = button("toolwindow.board", "btn.board") { openAction(BOARD_ACTION) }
  private val dashboardButton = button("toolwindow.dashboard", "btn.dashboard") { openAction(DASHBOARD_ACTION) }

  val treeHidden: Boolean get() = fold.hidden

  init {
    treeColumn.add(JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      add(treeTitle)
      add(treeHeader)
    }, BorderLayout.NORTH)
    treeColumn.add(tree, BorderLayout.CENTER)
    treeColumn.add(treeFooter, BorderLayout.SOUTH)
    treeColumn.minimumSize = JBUI.size(0, 0)
    add(topRow, BorderLayout.NORTH)
    add(center, BorderLayout.CENTER)
    addComponentListener(object : ComponentAdapter() {
      override fun componentResized(e: ComponentEvent) = relayout()
    })
    // floating <-> docked: the buttons move between the tree column and the full-width row
    project.messageBus.connect(parent).subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
      override fun stateChanged(toolWindowManager: ToolWindowManager, toolWindow: ToolWindow, changeType: ToolWindowManagerListener.ToolWindowManagerEventType) {
        if (toolWindow.id == AgentTerminalService.TOOL_WINDOW_ID) relayout()
      }
    })
    relayout()
  }

  /** Hides the tree column (the tabs get the whole width) or shows it again with the proportion it had; remembered. */
  fun toggleTree() {
    fold.toggle()
    props.setValue(SplitLayout.KEY_TREE_HIDDEN, fold.hidden, false)
    relayout()
    if (!fold.hidden) splitter?.proportion = fold.proportion
  }

  /** Places the buttons, the tree column and the split for the current tool window type and size. */
  fun relayout() {
    val type = typeOf()
    val inHeader = SplitLayout.buttonsInHeader(type, fold.hidden)
    val wanted = SplitLayout.orientation(type, width, height)
    // a resize sends this for every pixel; only a change of place, fold or orientation moves anything
    val key = Triple(inHeader, fold.hidden, wanted)
    if (splitter != null && key == laidOut) return
    laidOut = key
    topRow.removeAll()
    treeHeader.removeAll()
    treeFooter.removeAll()
    if (inHeader) {
      listOf(claudeButton, codexButton, boardButton, dashboardButton).forEach(topRow::add)
    } else {
      listOf(claudeButton, codexButton).forEach(treeHeader::add)
      listOf(boardButton, dashboardButton).forEach(treeFooter::add)
    }
    topRow.isVisible = inHeader
    treeHeader.isVisible = !inHeader
    treeFooter.isVisible = !inHeader
    treeColumn.isVisible = !fold.hidden
    strip.isVisible = fold.hidden
    if (splitter == null || orientation != wanted) rebuildSplitter(wanted)
    revalidate()
    repaint()
  }

  private fun rebuildSplitter(o: SplitOrientation) {
    val vertical = o == SplitOrientation.VERTICAL
    val s = OnePixelSplitter(
      vertical, if (vertical) SplitLayout.KEY_V else SplitLayout.KEY_H, if (vertical) SplitLayout.DEFAULT_V else SplitLayout.DEFAULT_H,
    )
    s.firstComponent = treeColumn
    s.secondComponent = tabs
    if (!fold.hidden) fold.proportion = s.proportion
    // a drag of the divider is remembered as the proportion to come back to
    s.addPropertyChangeListener(com.intellij.openapi.ui.Splitter.PROP_PROPORTION) { if (!fold.hidden) fold.proportion = s.proportion }
    center.removeAll()
    // the folded tree's strip sits where the tree was: left of the terminals, or above them when stacked
    strip.sideways = !vertical
    val arrow = if (vertical) AllIcons.General.ChevronUp else AllIcons.General.ChevronLeft
    foldButton.setIcons(arrow, arrow, arrow)
    center.add(strip, if (vertical) BorderLayout.NORTH else BorderLayout.WEST)
    center.add(s, BorderLayout.CENTER)
    splitter = s
    orientation = o
  }

  private fun newSession(kind: AgentKind) {
    val path = tree.selectedWorktree() ?: project.basePath ?: return
    launch(path, kind)
  }

  private fun button(key: String, name: String, run: () -> Unit) =
    JButton(PhantomAiBundle.message(key)).apply {
      this.name = name
      isFocusable = false
      addActionListener { run() }
    }

  @TestOnly internal fun fold(): TreeFold = fold

  @TestOnly internal fun splitterForTest(): OnePixelSplitter? = splitter

  @TestOnly internal fun topRowForTest(): JPanel = topRow

  @TestOnly internal fun treeColumnForTest(): JPanel = treeColumn

  @TestOnly internal fun treeHeaderForTest(): JPanel = treeHeader

  @TestOnly internal fun stripForTest(): FoldStrip = strip

  @TestOnly internal fun foldButtonForTest(): InplaceButton = foldButton

  @TestOnly internal fun centerForTest(): JPanel = center

  @TestOnly internal fun buttonForTest(name: String): JButton = listOf(claudeButton, codexButton, boardButton, dashboardButton).first { it.name == name }

  companion object {
    const val BOARD_ACTION = "dev.phantom.ai.OpenBoard"
    const val DASHBOARD_ACTION = "dev.phantom.ai.OpenAgentDashboard"

    /** Runs a registered action for [project]; nothing happens while there is no such action (Board before its editor exists). */
    fun runAction(project: Project, id: String) {
      val action = ActionManager.getInstance().getAction(id) ?: return
      val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build()
      ActionUtil.performAction(action, AnActionEvent.createEvent(context, null, "PhantomAiToolWindow", ActionUiKind.NONE, null))
    }
  }
}
