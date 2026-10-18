// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.ui

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.ui.tree.TreeUtil
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.exec.DbConnectionService
import dev.phantom.db.core.exec.DbTableRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/**
 * Selects a table or column in the Database tool window, for Ctrl+B on a name in SQL. Each level loads when it is
 * expanded, so this expands data source, schema and table in turn and waits for each to load.
 */
object DbTreeNavigation {
  private const val LEVEL_TIMEOUT_MS = 10_000L

  fun select(project: Project, dataSource: DbDataSource, table: DbTableRef, column: String?) {
    DbToolWindowFactory.show(project)
    DbConnectionService.getInstance(project).launch {
      withContext(Dispatchers.EDT) {
        val panel = ToolWindowManager.getInstance(project).getToolWindow(DbToolWindowFactory.ID)
                      ?.contentManager?.contents?.firstNotNullOfOrNull { it.component as? DbExplorerPanel } ?: return@withContext
        val tree = panel.tree
        val root = tree.model.root as DefaultMutableTreeNode
        var node = child(root) { (it as? DbNode.Source)?.dataSource?.id == dataSource.id } ?: return@withContext
        val schemaName = table.schema ?: table.catalog
        node = expandTo(tree, node) { (it as? DbNode.Schema)?.ref?.name == schemaName } ?: return@withContext
        node = expandTo(tree, node) { (it as? DbNode.Table)?.ref?.name == table.name } ?: return@withContext
        if (column != null) node = expandTo(tree, node) { (it as? DbNode.Column)?.info?.name == column } ?: node
        TreeUtil.selectPath(tree, TreePath(node.path))
        tree.requestFocusInWindow()
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
