// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.util.ui.tree.TreeUtil
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.PhantomDbBundle
import dev.phantom.db.core.SqlDbTarget
import dev.phantom.db.core.SqlFile
import dev.phantom.db.core.exec.DbConsoles
import dev.phantom.db.core.exec.DbConnectionService
import dev.phantom.db.core.exec.DbRoutine
import dev.phantom.db.core.exec.DbTableRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/**
 * Selects a table, column or routine in the Database tool window, for Ctrl+B on a name in SQL and Go to Database
 * Object. Each level loads when it is expanded, so this expands data source, schema, folder and table in turn and waits
 * for each to load. The tree filter is cleared first, since it may hide the target.
 */
object DbTreeNavigation {
  private const val LEVEL_TIMEOUT_MS = 10_000L

  /** [focus]: move the keyboard focus to the tree (not when an editor opens next). */
  fun select(project: Project, dataSource: DbDataSource, table: DbTableRef, column: String?, focus: Boolean = true) {
    val folder = when {
      table.isMaterializedView -> DbNode.Folder.Kind.MATERIALIZED_VIEWS
      table.isView -> DbNode.Folder.Kind.VIEWS
      else -> DbNode.Folder.Kind.TABLES
    }
    select(project, dataSource, table.schema ?: table.catalog, folder, column, focus) { (it as? DbNode.Table)?.ref?.name.equals(table.name, ignoreCase = true) }
  }

  fun select(project: Project, dataSource: DbDataSource, routine: DbRoutine, focus: Boolean = true) {
    select(project, dataSource, routine.schema.name, DbNode.Folder.Kind.ROUTINES, null, focus) {
      (it as? DbNode.Routine)?.routine?.let { r -> r.name == routine.name && r.function == routine.function } == true
    }
  }

  /** The data source's own node. */
  fun select(project: Project, dataSource: DbDataSource, focus: Boolean = true) {
    DbToolWindowFactory.show(project, focus)
    DbConnectionService.getInstance(project).launch {
      withContext(Dispatchers.EDT) {
        val panel = DbExplorerPanel.find(project) ?: return@withContext
        panel.setFilter("")
        val tree = panel.tree
        val node = panel.sourceNodes().firstOrNull { (it.userObject as DbNode.Source).dataSource.id == dataSource.id } ?: return@withContext
        TreeUtil.selectPath(tree, TreePath(node.path))
        if (focus) tree.requestFocusInWindow()
      }
    }
  }

  private fun select(project: Project, dataSource: DbDataSource, schemaName: String?, folder: DbNode.Folder.Kind, column: String?,
                     focus: Boolean, matches: (Any?) -> Boolean) {
    DbToolWindowFactory.show(project, focus)
    DbConnectionService.getInstance(project).launch {
      withContext(Dispatchers.EDT) {
        // the first time the tool window opens, its panel is made after show() returns
        val panel = awaitPanel(project) ?: return@withContext
        panel.setFilter("")
        val tree = panel.tree
        val node = locate(panel, dataSource, schemaName, folder, matches)
        if (node == null) {
          NotificationGroupManager.getInstance().getNotificationGroup("Phantom Database")
            .createNotification(PhantomDbBundle.message("db.tree.navigation.not.found", listOfNotNull(schemaName, dataSource.name).joinToString(" · ")),
                                NotificationType.INFORMATION)
            .notify(project)
          return@withContext
        }
        val target = if (column != null) expandTo(tree, node) { (it as? DbNode.Column)?.info?.name.equals(column, ignoreCase = true) } ?: node else node
        TreeUtil.selectPath(tree, TreePath(target.path))
        TreeUtil.scrollToVisible(tree, TreePath(target.path), false)
        if (focus) tree.requestFocusInWindow()
      }
    }
  }

  private suspend fun awaitPanel(project: Project): DbExplorerPanel? {
    val deadline = System.currentTimeMillis() + LEVEL_TIMEOUT_MS
    while (System.currentTimeMillis() < deadline) {
      DbExplorerPanel.find(project)?.let { return it }
      delay(50)
    }
    return null
  }

  /** The object's node: under its schema (names compared as the database does, ignoring case), else under the data source. */
  private suspend fun locate(panel: DbExplorerPanel, dataSource: DbDataSource, schemaName: String?, folder: DbNode.Folder.Kind,
                             matches: (Any?) -> Boolean): DefaultMutableTreeNode? {
    val tree = panel.tree
    val source = panel.sourceNodes().firstOrNull { (it.userObject as DbNode.Source).dataSource.id == dataSource.id } ?: return null
    val schema = schemaName?.let { name -> expandTo(tree, source) { (it as? DbNode.Schema)?.ref?.name.equals(name, ignoreCase = true) } }
    // a data source that lists its folders without a schema level, or a schema it does not show
    val parent = schema ?: source
    val folderNode = expandTo(tree, parent) { (it as? DbNode.Folder)?.kind == folder } ?: return null
    return expandTo(tree, folderNode, matches)
  }

  private fun child(parent: DefaultMutableTreeNode, matches: (Any?) -> Boolean): DefaultMutableTreeNode? =
    (0 until parent.childCount).map { parent.getChildAt(it) as DefaultMutableTreeNode }.firstOrNull { matches(it.userObject) }

  private suspend fun expandTo(tree: com.intellij.ui.treeStructure.Tree, parent: DefaultMutableTreeNode, matches: (Any?) -> Boolean): DefaultMutableTreeNode? {
    tree.expandPath(TreePath(parent.path))
    val deadline = System.currentTimeMillis() + LEVEL_TIMEOUT_MS
    while (System.currentTimeMillis() < deadline) {
      child(parent, matches)?.let { return it }
      if (parent.childCount > 0 && (parent.getChildAt(0) as DefaultMutableTreeNode).userObject != DbNode.Loading) return null
      delay(50)
    }
    return null
  }
}

/**
 * Select in Database View (Alt+Shift+B, as in DataGrip): the table or column at the caret of a `.sql` file or console,
 * else the file's data source, selected in the Database tree.
 */
class DbSelectInTreeAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.PSI_FILE) is SqlFile
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val file = e.getData(CommonDataKeys.PSI_FILE) as? SqlFile ?: return
    val offset = e.getData(CommonDataKeys.EDITOR)?.caretModel?.offset
    val target = offset?.let { file.findReferenceAt(it)?.resolve() as? SqlDbTarget }
    if (target != null) {
      DbTreeNavigation.select(project, target.dataSource, target.table, target.column?.name)
      return
    }
    DbConsoles.dataSourceFor(project, file.virtualFile)?.let { DbTreeNavigation.select(project, it) }
  }
}
