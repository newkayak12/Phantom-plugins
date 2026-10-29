package dev.phantom.ai

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowType
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RunsInEdt
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.SessionStore
import dev.phantom.ai.sidebar.WorktreeTreePanel
import dev.phantom.ai.terminal.AgentTab
import dev.phantom.ai.terminal.AgentTabsHost
import dev.phantom.ai.terminal.AgentTabsPanel
import dev.phantom.ai.terminal.AgentTerminalService
import dev.phantom.ai.terminal.StartedTerminal
import dev.phantom.ai.terminal.TerminalStarter
import dev.phantom.ai.toolwindow.AgentToolWindowPanel
import dev.phantom.ai.toolwindow.SplitLayout
import java.nio.file.Files
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * In-IDE tests of the one-panel tool window, on a JUnit 4 project rule so the cases keep their plain names. They run on the
 * EDT: setSize queues a componentResized whose relayout() would otherwise race the test thread's toggleTree().
 */
@RunsInEdt
class AgentToolWindowInIdeTest {
  companion object {
    @ClassRule @JvmField val projectRule = ProjectRule()
  }

  @Rule @JvmField val edtRule = EdtRule()
  @Rule @JvmField val disposableRule = DisposableRule()
  private val project get() = projectRule.project
  private val testRootDisposable get() = disposableRule.disposable

  private var type = ToolWindowType.DOCKED
  private val launched = mutableListOf<Pair<String, AgentKind>>()
  private val actions = mutableListOf<String>()
  private val host get() = AgentTabsHost.getInstance(project)

  @Before fun setUp() {
    PropertiesComponent.getInstance().unsetValue(SplitLayout.KEY_TREE_HIDDEN)
  }

  @After fun tearDown() {
    PropertiesComponent.getInstance().unsetValue(SplitLayout.KEY_TREE_HIDDEN)
    host.tabs().forEach { host.close(it.id) }
    SessionStore.getInstance().all().forEach { SessionStore.getInstance().remove(it.tabId) }
  }

  private fun panel(): AgentToolWindowPanel {
    val tree = WorktreeTreePanel(project, background = { })
    Disposer.register(testRootDisposable, tree)
    return AgentToolWindowPanel(
      project, testRootDisposable, typeOf = { type }, tree = tree,
      launch = { path, kind -> launched += path to kind }, openAction = { actions += it },
    )
  }

  @Test fun treeToggleHidesAndRestores() {
    val p = panel()
    p.setSize(1000, 500)
    p.relayout()
    assertTrue(p.treeColumnForTest().isVisible)
    p.splitterForTest()!!.proportion = 0.4f
    p.toggleTree()
    assertFalse(p.treeColumnForTest().isVisible)
    assertEquals(0f, p.fold().effectiveProportion())
    assertTrue("with the tree hidden the buttons are in the full-width row", p.topRowForTest().isVisible)
    p.toggleTree()
    assertTrue(p.treeColumnForTest().isVisible)
    assertEquals(0.4f, p.fold().effectiveProportion(), 0.001f)
    assertEquals(0.4f, p.splitterForTest()!!.proportion, 0.001f)
  }

  @Test fun treeHiddenStateSurvivesRecreate() {
    val first = panel()
    first.toggleTree()
    assertTrue(PropertiesComponent.getInstance().getBoolean(SplitLayout.KEY_TREE_HIDDEN, false))
    val second = panel()
    assertTrue(second.treeHidden)
    assertFalse(second.treeColumnForTest().isVisible)
    second.toggleTree()
    assertFalse(panel().treeHidden)
  }

  @Test fun dockedButtonsInTreeHeader() {
    type = ToolWindowType.DOCKED
    val p = panel()
    assertFalse(p.topRowForTest().isVisible)
    assertTrue(p.treeHeaderForTest().isVisible)
    assertSame(p.treeHeaderForTest(), p.buttonForTest("btn.claude").parent)
    assertSame(p.treeHeaderForTest(), p.buttonForTest("btn.codex").parent)
    assertSame(p.buttonForTest("btn.board").parent, p.buttonForTest("btn.dashboard").parent)
    assertNotSame(p.treeHeaderForTest(), p.buttonForTest("btn.board").parent)
    assertEquals("+ Claude", p.buttonForTest("btn.claude").text)
    assertTrue(p.treeColumnForTest().isAncestorOf(p.buttonForTest("btn.board")))
  }

  @Test fun floatingButtonsInFullWidthRow() {
    type = ToolWindowType.FLOATING
    val p = panel()
    assertTrue(p.topRowForTest().isVisible)
    listOf("btn.claude", "btn.codex", "btn.board", "btn.dashboard").forEach { assertSame(it, p.topRowForTest(), p.buttonForTest(it).parent) }
    assertFalse(p.treeHeaderForTest().isVisible)
    type = ToolWindowType.DOCKED
    p.relayout()
    assertFalse(p.topRowForTest().isVisible)
    assertSame(p.treeHeaderForTest(), p.buttonForTest("btn.claude").parent)
  }

  @Test fun floatingSplitReorientsWithSize() {
    type = ToolWindowType.FLOATING
    val p = panel()
    p.setSize(1200, 500)
    p.relayout()
    assertFalse(p.splitterForTest()!!.isVertical)
    p.setSize(400, 800)
    p.relayout()
    assertTrue(p.splitterForTest()!!.isVertical)
  }

  @Test fun dockedNarrowStaysSideBySide() {
    type = ToolWindowType.DOCKED
    val p = panel()
    p.setSize(400, 800)
    p.relayout()
    assertFalse(p.splitterForTest()!!.isVertical)
  }

  @Test fun buttonsStartAgentsAndRunActions() {
    val p = panel()
    p.buttonForTest("btn.claude").doClick()
    p.buttonForTest("btn.codex").doClick()
    p.buttonForTest("btn.board").doClick()
    p.buttonForTest("btn.dashboard").doClick()
    val base = project.basePath
    if (base != null) assertEquals(listOf(AgentKind.CLAUDE, AgentKind.CODEX), launched.map { it.second })
    assertEquals(listOf(AgentToolWindowPanel.BOARD_ACTION, AgentToolWindowPanel.DASHBOARD_ACTION), actions)
    AgentToolWindowPanel.runAction(project, "dev.phantom.ai.NoSuchAction") // null-safe
  }

  @Test fun tabsHostOrderSelectAndCloseDisposes() {
    val disposed = mutableListOf<String>()
    fun tab(id: String) = AgentTab(id, "claude · $id", JPanel(), null, null, Disposer.newDisposable(id).also { d -> Disposer.register(d) { disposed += id } })
    host.add(tab("a")); host.add(tab("b")); host.add(tab("c"))
    assertEquals(listOf("a", "b", "c"), host.tabs().map { it.id })
    assertEquals("c", host.selected()!!.id)
    assertTrue(host.select("a"))
    assertFalse(host.select("nope"))
    host.close("a")
    assertEquals(listOf("a"), disposed)
    assertEquals(listOf("b", "c"), host.tabs().map { it.id })
    assertEquals("b", host.selected()!!.id)
  }

  @Test fun tabsPanelShowsPlaceholderThenClosableTabs() {
    val panel = AgentTabsPanel(project, testRootDisposable)
    assertTrue(panel.showsPlaceholder())
    host.add(AgentTab("t1", "claude · main", JPanel(), null, null, Disposer.newDisposable("t1")))
    assertEquals(listOf("claude · main"), panel.tabTitles())
    assertFalse(panel.showsPlaceholder())
    assertNotNull(findLabel(panel, "agent.tab.close.t1"))
    host.close("t1")
    assertTrue(panel.showsPlaceholder())
  }

  private fun findLabel(c: java.awt.Component, name: String): JLabel? {
    if (c is JLabel && c.name == name) return c
    if (c is java.awt.Container) c.components.forEach { findLabel(it, name)?.let { l -> return l } }
    return null
  }

  @Test fun launchTitlesByBranchElseFolder() {
    AgentTerminalService.getInstance(project).replaceStarter(TerminalStarter { _: Disposable, _, _, _ -> StartedTerminal(JPanel()) })
    val dir = Files.createTempDirectory("phantom-ai-branch")
    try {
      Files.createDirectories(dir.resolve(".git"))
      Files.writeString(dir.resolve(".git/HEAD"), "ref: refs/heads/feat/x\n")
      com.intellij.testFramework.runInEdtAndWait {
        AgentTerminalService.getInstance(project).launch(dir.toString(), AgentKind.CLAUDE)!!
        AgentTerminalService.getInstance(project).launch("/w/repo/folder", AgentKind.CODEX)!!
        AgentTerminalService.getInstance(project).launch("/w/repo/folder", AgentKind.CODEX, label = "custom")!!
      }
      assertEquals(listOf("claude · feat/x", "codex · folder", "codex · custom"), host.tabs().map { it.title })
    } finally {
      dir.toFile().deleteRecursively()
    }
  }
}
