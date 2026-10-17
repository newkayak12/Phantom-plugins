// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.core.ide

import com.intellij.icons.AllIcons
import com.intellij.microservices.endpoints.EndpointsFilter
import com.intellij.microservices.endpoints.EndpointsProvider
import com.intellij.microservices.endpoints.presentation.EndpointMethodPresentation
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import dev.phantom.spring.core.PhantomSpringBundle
import dev.phantom.spring.core.SpringProjectGate
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * The Endpoints tool window: every HTTP endpoint the [EndpointsProvider]s report, grouped by module, with a filter
 * field. Enter or double-click opens the handler. Lists are built in the background when the window is visible and
 * rebuilt after code changes; nothing is computed while the window stays closed.
 */
internal class EndpointsToolWindowFactory : ToolWindowFactory {
  override suspend fun isApplicableAsync(project: Project): Boolean = SpringProjectGate.isSpringProject(project)

  override fun shouldBeAvailable(project: Project): Boolean = true

  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val panel = EndpointsPanel(project, toolWindow.disposable)
    toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, null, false))
    toolWindow.setTitleActions(listOf(object : DumbAwareAction(PhantomSpringBundle.message("endpoints.refresh"), null, AllIcons.Actions.Refresh) {
      override fun getActionUpdateThread() = ActionUpdateThread.EDT
      override fun actionPerformed(e: AnActionEvent) = panel.refresh()
    }))
  }
}

/** No filtering: the whole project. */
private object AllEndpoints : EndpointsFilter

/** One row: what the provider says about an endpoint, plus how to open it. */
internal class EndpointRow(val url: String, val methods: List<String>, val location: String, val target: () -> PsiElement?) {
  fun matches(query: String): Boolean {
    if (query.isBlank()) return true
    val words = query.trim().lowercase().split(Regex("\\s+"))
    val text = "${methods.joinToString(" ")} $url $location".lowercase()
    return words.all { it in text }
  }
}

internal class EndpointsPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()), Disposable {
  private val root = DefaultMutableTreeNode()
  private val model = DefaultTreeModel(root)
  private val tree = Tree(model)
  private val search = SearchTextField(false)
  private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
  private var groups: List<Pair<String, List<EndpointRow>>> = emptyList()
  private var lastStamp = -1L

  init {
    Disposer.register(parent, this)
    tree.isRootVisible = false
    tree.showsRootHandles = true
    tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
    tree.cellRenderer = Renderer()
    tree.emptyText.text = PhantomSpringBundle.message("endpoints.loading")
    search.textEditor.emptyText.text = PhantomSpringBundle.message("endpoints.search.hint")
    search.addDocumentListener(object : DocumentAdapter() {
      override fun textChanged(e: DocumentEvent) = rebuild()
    })
    search.textEditor.addKeyListener(object : KeyAdapter() {
      override fun keyPressed(e: KeyEvent) {
        if (e.keyCode == KeyEvent.VK_DOWN) { tree.requestFocusInWindow(); TreeUtil.promiseSelectFirstLeaf(tree); e.consume() }
      }
    })
    tree.addKeyListener(object : KeyAdapter() {
      override fun keyPressed(e: KeyEvent) {
        if (e.keyCode == KeyEvent.VK_ENTER) { open(); e.consume() }
      }
    })
    object : DoubleClickListener() {
      override fun onDoubleClick(event: MouseEvent): Boolean = open()
    }.installOn(tree)
    PopupHandler.installPopupMenu(tree, DefaultActionGroup(CopyUrlAction(), OpenAction()), "EndpointsPopup")

    search.border = JBUI.Borders.empty(4, 6)
    add(search, BorderLayout.NORTH)
    add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER)

    project.messageBus.connect(this).subscribe(PsiModificationTracker.TOPIC, PsiModificationTracker.Listener { scheduleRefresh() })
    refresh()
  }

  private fun scheduleRefresh() {
    if (!isShowing) return
    alarm.cancelAllRequests()
    alarm.addRequest(::refresh, 1500)
  }

  override fun addNotify() {
    super.addNotify()
    // Changes made while the window was hidden
    if (PsiModificationTracker.getInstance(project).modificationCount != lastStamp) refresh()
  }

  fun refresh() {
    val stamp = PsiModificationTracker.getInstance(project).modificationCount
    ReadAction.nonBlocking<List<Pair<String, List<EndpointRow>>>> { collect() }
      .inSmartMode(project)
      .expireWith(this)
      .coalesceBy(this)
      .finishOnUiThread(ModalityState.any()) { result ->
        lastStamp = stamp
        groups = result
        rebuild()
      }
      .submit(AppExecutorUtil.getAppExecutorService())
  }

  private fun collect(): List<Pair<String, List<EndpointRow>>> {
    val byModule = LinkedHashMap<String, MutableList<EndpointRow>>()
    for (provider in EndpointsProvider.getAvailableProviders(project)) {
      @Suppress("UNCHECKED_CAST")
      val typed = provider as EndpointsProvider<Any, Any>
      for (group in typed.getEndpointGroups(project, AllEndpoints)) {
        for (endpoint in typed.getEndpoints(group)) {
          if (!typed.isValidEndpoint(group, endpoint)) continue
          val presentation = typed.getEndpointPresentation(group, endpoint)
          val methods = (presentation as? EndpointMethodPresentation)?.endpointMethods.orEmpty()
          val row = EndpointRow(presentation.presentableText ?: "", methods, presentation.locationString ?: "") {
            typed.getNavigationElement(group, endpoint)
          }
          byModule.getOrPut(moduleName(group)) { ArrayList() } += row
        }
      }
    }
    return byModule.map { (module, rows) -> module to rows.sortedWith(compareBy({ it.url }, { it.methods.firstOrNull() ?: "" })) }
      .sortedBy { it.first }
  }

  private fun moduleName(group: Any): String =
    ((group as? SpringEndpointsGroup)?.module as Module?)?.name ?: PhantomSpringBundle.message("endpoints.no.module")

  private fun rebuild() {
    val query = search.text
    root.removeAllChildren()
    for ((module, rows) in groups) {
      val visible = rows.filter { it.matches(query) }
      if (visible.isEmpty()) continue
      val node = DefaultMutableTreeNode(module)
      visible.forEach { node.add(DefaultMutableTreeNode(it, false)) }
      root.add(node)
    }
    model.reload()
    TreeUtil.expandAll(tree)
    tree.emptyText.text = if (groups.isEmpty()) PhantomSpringBundle.message("endpoints.empty") else PhantomSpringBundle.message("endpoints.no.match")
  }

  private fun selected(): EndpointRow? = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? EndpointRow

  private fun open(): Boolean {
    val row = selected() ?: return false
    ReadAction.nonBlocking<Navigatable?> { row.target() as? Navigatable }
      .expireWith(this)
      .finishOnUiThread(ModalityState.defaultModalityState()) { it?.navigate(true) }
      .submit(AppExecutorUtil.getAppExecutorService())
    return true
  }

  override fun dispose() {}

  private inner class CopyUrlAction : DumbAwareAction(PhantomSpringBundle.message("endpoints.copy.url"), null, AllIcons.Actions.Copy) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = selected() != null }
    override fun actionPerformed(e: AnActionEvent) {
      selected()?.let { CopyPasteManager.getInstance().setContents(StringSelection(it.url)) }
    }
  }

  private inner class OpenAction : DumbAwareAction(PhantomSpringBundle.message("endpoints.open"), null, AllIcons.Actions.EditSource) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = selected() != null }
    override fun actionPerformed(e: AnActionEvent) { open() }
  }

  private class Renderer : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
      when (val item = (value as? DefaultMutableTreeNode)?.userObject) {
        is String -> {
          icon = AllIcons.Nodes.Module
          append(item, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        }
        is EndpointRow -> {
          val methods = item.methods.ifEmpty { listOf("ANY") }
          append(methods.joinToString("|").padEnd(7), SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, methodColor(methods.first())))
          append(" ")
          append(item.url, SimpleTextAttributes.REGULAR_ATTRIBUTES)
          append("   ${item.location}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
      }
    }

    private fun methodColor(method: String): JBColor = when (method) {
      "GET" -> JBColor(0x2E7D32, 0x6FBF73)
      "POST" -> JBColor(0xB5711F, 0xE2A65A)
      "PUT", "PATCH" -> JBColor(0x1565C0, 0x6CA6E8)
      "DELETE" -> JBColor(0xC62828, 0xE57373)
      else -> JBColor(0x616161, 0x9E9E9E)
    }
  }
}
