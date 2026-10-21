// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.ui

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.util.ui.tree.TreeUtil
import dev.phantom.db.core.DbDataSource
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
    select(project, dataSource, table.schema ?: table.catalog, folder, column, focus) { (it as? DbNode.Table)?.ref?.name == table.name }
  }

  fun select(project: Project, dataSource: DbDataSource, routine: DbRoutine, focus: Boolean = true) {
    select(project, dataSource, routine.schema.name, DbNode.Folder.Kind.ROUTINES, null, focus) {
      (it as? DbNode.Routine)?.routine?.let { r -> r.name == routine.name && r.function == routine.function } == true
    }
  }

  private fun select(project: Project, dataSource: DbDataSource, schemaName: String?, folder: DbNode.Folder.Kind, column: String?,
                     focus: Boolean, matches: (Any?) -> Boolean) {
    DbToolWindowFactory.show(project, focus)
    DbConnectionService.getInstance(project).launch {
      withContext(Dispatchers.EDT) {
        val panel = DbExplorerPanel.find(project) ?: return@withContext
        panel.setFilter("")
        val tree = panel.tree
        val root = tree.model.root as DefaultMutableTreeNode
        var node = child(root) { (it as? DbNode.Source)?.dataSource?.id == dataSource.id } ?: return@withContext
        node = expandTo(tree, node) { (it as? DbNode.Schema)?.ref?.name == schemaName } ?: return@withContext
        node = expandTo(tree, node) { (it as? DbNode.Folder)?.kind == folder } ?: return@withContext
        node = expandTo(tree, node, matches) ?: return@withContext
        if (column != null) node = expandTo(tree, node) { (it as? DbNode.Column)?.info?.name == column } ?: node
        TreeUtil.selectPath(tree, TreePath(node.path))
        if (focus) tree.requestFocusInWindow()
      }
    }
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
