// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.RegisterToolWindowTask
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import dev.phantom.db.core.*
import dev.phantom.db.core.PhantomDbIcons
import dev.phantom.db.core.exec.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.function.Supplier
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * Database Changes: what the open transactions of manual-commit data sources contain, with Commit and Rollback.
 * The tool window appears once a data source is switched to manual commit.
 */
class DbChangesToolWindowFactory : ToolWindowFactory, DumbAware {
  override suspend fun isApplicableAsync(project: Project): Boolean =
    DbDataSourceService.getInstance(project).dataSources.any { it.manualCommit && !it.readOnly }

  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val panel = DbChangesPanel(project, toolWindow.disposable)
    toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, null, false))
  }

  companion object {
    const val ID: String = "Database Changes"

    /** Registers the tool window when a data source turned manual commit on after the project opened. */
    fun ensureRegistered(project: Project): ToolWindow {
      val manager = ToolWindowManager.getInstance(project)
      return manager.getToolWindow(ID) ?: manager.registerToolWindow(
        RegisterToolWindowTask(ID, ToolWindowAnchor.BOTTOM, contentFactory = DbChangesToolWindowFactory(), icon = AllIcons.Vcs.Changelist,
                               stripeTitle = Supplier { PhantomDbBundle.message("toolwindow.stripe.Database_Changes") }))
    }
  }
}

class DbChangesPanel(private val project: Project, parent: Disposable) : SimpleToolWindowPanel(true, true) {
  private val root = DefaultMutableTreeNode()
  private val model = DefaultTreeModel(root)
  private val tree = Tree(model)
  private val pending = DbPendingChanges.getInstance(project)

  init {
    tree.isRootVisible = false
    tree.showsRootHandles = true
    tree.emptyText.text = PhantomDbBundle.message("db.changes.empty")
    tree.emptyText.appendSecondaryText(PhantomDbBundle.message("db.changes.empty.hint"), SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
    tree.cellRenderer = Renderer()
    setContent(ScrollPaneFactory.createScrollPane(tree, true))
    val group = DefaultActionGroup(EndAction(commit = true), EndAction(commit = false))
    toolbar = ActionManager.getInstance().createActionToolbar("PhantomDbChanges", group, true).also { it.targetComponent = tree }.component
    pending.addListener(parent) { ApplicationManager.getApplication().invokeLater({ rebuild() }, { project.isDisposed }) }
    rebuild()
  }

  private fun rebuild() {
    root.removeAllChildren()
    for (ds in pending.dataSourcesWithChanges()) {
      val node = DefaultMutableTreeNode(ds)
      pending.of(ds.id).forEach { node.add(DefaultMutableTreeNode(it)) }
      root.add(node)
    }
    model.reload()
    TreeUtil.expandAll(tree)
  }

  /** The data source the selection belongs to; with nothing selected, the only one that has changes. */
  private fun target(): DbDataSource? {
    val node = tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode
    return when (val value = node?.userObject) {
      is DbDataSource -> value
      is DbPendingChange -> DbDataSourceService.getInstance(project).find(value.dataSourceId)
      else -> pending.dataSourcesWithChanges().singleOrNull()
    }
  }

  private inner class EndAction(private val commit: Boolean) : DumbAwareAction(
    PhantomDbBundle.message(if (commit) "db.changes.commit" else "db.changes.rollback"), null,
    if (commit) AllIcons.Actions.Commit else AllIcons.Actions.Rollback) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
      e.presentation.isEnabled = target() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
      val ds = target() ?: return
      DbConnectionService.getInstance(project).launch {
        val failure = runCatching { if (commit) pending.commit(ds) else pending.rollback(ds) }.exceptionOrNull()
        if (failure != null) withContext(Dispatchers.EDT) {
          Messages.showErrorDialog(project, failure.message ?: failure.javaClass.simpleName, PhantomDbBundle.message("toolwindow.stripe.Database_Changes"))
        }
      }
    }
  }

  private class Renderer : ColoredTreeCellRenderer() {
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
      when (val item = (value as? DefaultMutableTreeNode)?.userObject) {
        is DbDataSource -> {
          icon = PhantomDbIcons.forDataSource(item)
          append(item.name)
          append("  " + PhantomDbBundle.message("db.changes.count", (value as DefaultMutableTreeNode).childCount), SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
        is DbPendingChange -> {
          icon = AllIcons.Actions.Edit
          append(SqlSafety.preview(item.sql))
          append("  " + PhantomDbBundle.message("db.changes.rows", item.rows, time.format(item.at)), SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
      }
    }
  }
}
