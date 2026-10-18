// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
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
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import dev.phantom.db.core.*
import dev.phantom.db.core.exec.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.event.MouseEvent
import java.sql.SQLException
import java.util.function.Supplier
import javax.swing.JTree
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * The Database tool window. It shows up only in projects that have a data source or a JDBC driver among their
 * libraries (checked by library name, no file access); elsewhere Tools → Database Console opens it on demand.
 */
class DbToolWindowFactory : ToolWindowFactory, DumbAware {
  override suspend fun isApplicableAsync(project: Project): Boolean =
    DbDataSourceService.getInstance(project).dataSources.isNotEmpty() || readAction { DbDrivers.projectHasDriverLibrary(project) }

  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val panel = DbExplorerPanel(project, toolWindow.disposable)
    toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, null, false))
  }

  companion object {
    const val ID: String = "Database"

    /** Opens the tool window, registering it first when the project did not qualify at opening. */
    fun show(project: Project) {
      val manager = ToolWindowManager.getInstance(project)
      val toolWindow = manager.getToolWindow(ID) ?: manager.registerToolWindow(
        RegisterToolWindowTask(ID, ToolWindowAnchor.RIGHT, contentFactory = DbToolWindowFactory(), icon = AllIcons.Nodes.DataTables,
                               stripeTitle = Supplier { PhantomDbBundle.message("toolwindow.stripe.Database") }))
      toolWindow.activate(null)
    }
  }
}

class DbShowToolWindowAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    DbToolWindowFactory.show(e.project ?: return)
  }
}

/** Tree node payloads. */
sealed interface DbNode {
  class Source(val dataSource: DbDataSource) : DbNode
  class Schema(val dataSource: DbDataSource, val ref: DbSchemaRef) : DbNode
  class Table(val dataSource: DbDataSource, val ref: DbTableRef) : DbNode
  class Column(val info: DbColumnInfo) : DbNode
  data object Loading : DbNode
  class Message(val text: String, val error: Boolean) : DbNode
}

/**
 * Data sources → schemas → tables and views → columns. Children are loaded when a node is first expanded, on the
 * schema-tree connection, so a long console query does not block browsing.
 */
class DbExplorerPanel(private val project: Project, parent: Disposable) : SimpleToolWindowPanel(true, true) {
  private val root = DefaultMutableTreeNode()
  private val model = DefaultTreeModel(root)
  val tree: Tree = Tree(model)
  private val loading = HashSet<DefaultMutableTreeNode>()

  init {
    tree.isRootVisible = false
    tree.showsRootHandles = true
    tree.cellRenderer = Renderer()
    tree.emptyText.text = PhantomDbBundle.message("db.tree.empty")
    tree.emptyText.appendSecondaryText(PhantomDbBundle.message("db.action.add.text"), SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { add() }
    tree.addTreeWillExpandListener(object : TreeWillExpandListener {
      override fun treeWillExpand(event: TreeExpansionEvent) = load(event.path.lastPathComponent as DefaultMutableTreeNode)
      override fun treeWillCollapse(event: TreeExpansionEvent) {}
    })
    object : DoubleClickListener() {
      override fun onDoubleClick(event: MouseEvent): Boolean {
        val node = selectedNode() ?: return false
        return when (node) {
          is DbNode.Source -> { openConsole(node.dataSource, null); true }
          is DbNode.Table -> { openConsole(node.dataSource, selectAll(node)); true }
          else -> false
        }
      }
    }.installOn(tree)

    val group = DefaultActionGroup(
      action("db.action.add.text", AllIcons.General.Add) { add() },
      action("db.action.edit.text", AllIcons.Actions.Edit, { selectedSource() != null }) { edit() },
      action("db.action.remove.text", AllIcons.General.Remove, { selectedSource() != null }) { remove() },
      Separator.getInstance(),
      action("db.action.refresh.text", AllIcons.Actions.Refresh) { refresh() },
      action("db.action.console.text", AllIcons.Debugger.Console, { selectedSource() != null }) { selectedSource()?.let { openConsole(it, null) } },
      action("db.action.compare.text", AllIcons.Actions.Diff, { selectedNode() is DbNode.Schema }) {
        (selectedNode() as? DbNode.Schema)?.let { DbSchemaCompare.chooseAndCompare(project, DbSchemaSide(it.dataSource, it.ref), tree) }
      },
    )
    val toolbar = ActionManager.getInstance().createActionToolbar("PhantomDbExplorer", group, true)
    toolbar.targetComponent = tree
    setToolbar(toolbar.component)
    setContent(ScrollPaneFactory.createScrollPane(tree, true))
    PopupHandler.installPopupMenu(tree, group, "PhantomDbExplorerPopup")

    DbDataSourceService.getInstance(project).addListener(parent) {
      com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({ reload() }, project.disposed)
    }
    reload()
  }

  /** Rebuilds the data source level; expanded data sources stay expanded and reload their children. */
  fun reload() {
    val expanded = TreeUtil.collectExpandedUserObjects(tree).filterIsInstance<DbNode.Source>().map { it.dataSource.id }.toSet()
    root.removeAllChildren()
    for (ds in DbDataSourceService.getInstance(project).dataSources) root.add(expandable(DbNode.Source(ds)))
    model.reload()
    for (i in 0 until root.childCount) {
      val node = root.getChildAt(i) as DefaultMutableTreeNode
      if ((node.userObject as DbNode.Source).dataSource.id in expanded) tree.expandPath(TreePath(node.path))
    }
  }

  private fun load(node: DefaultMutableTreeNode) {
    val placeholder = (node.firstOrNull())?.userObject
    if (placeholder != DbNode.Loading || node.getClientLoading()) return
    node.setClientLoading(true)
    val payload = node.userObject as? DbNode ?: return
    val cache = DbMetadataCache.getInstance(project)
    DbConnectionService.getInstance(project).launch {
      val children: List<DbNode> = try {
        when (payload) {
          is DbNode.Source -> cache.schemas(payload.dataSource).map { DbNode.Schema(payload.dataSource, it) }
          is DbNode.Schema -> cache.tables(payload.dataSource, payload.ref).map { DbNode.Table(payload.dataSource, it) }
          is DbNode.Table -> cache.columns(payload.dataSource, payload.ref).map { DbNode.Column(it) }
          else -> emptyList()
        }.ifEmpty { listOf(DbNode.Message(PhantomDbBundle.message("db.tree.nothing"), false)) }
      }
      catch (e: SQLException) {
        listOf(DbNode.Message(DbExecutor.message(e), true))
      }
      catch (e: DbException) {
        listOf(DbNode.Message(e.message.orEmpty(), true))
      }
      withContext(Dispatchers.EDT) {
        node.removeAllChildren()
        for (child in children) node.add(if (child is DbNode.Column || child is DbNode.Message) DefaultMutableTreeNode(child, false) else expandable(child))
        node.setClientLoading(false)
        model.nodeStructureChanged(node)
        // a data source with a single schema opens straight into it
        if (payload is DbNode.Source && children.size == 1 && children[0] is DbNode.Schema) {
          tree.expandPath(TreePath((node.firstChild as DefaultMutableTreeNode).path))
        }
      }
    }
  }

  private fun refresh() {
    val cache = DbMetadataCache.getInstance(project)
    val sources = selectedSource()?.let { listOf(it) } ?: DbDataSourceService.getInstance(project).dataSources
    sources.forEach { cache.invalidate(it.id) }
    reload()
  }

  private fun add() {
    DbDataSourceDialog.create(project)
  }

  private fun edit() {
    val ds = selectedSource() ?: return
    DbDataSourceDialog.edit(project, ds)
  }

  private fun remove() {
    val ds = selectedSource() ?: return
    if (Messages.showYesNoDialog(project, PhantomDbBundle.message("db.remove.confirm", ds.name), PhantomDbBundle.message("db.action.remove.text"),
                                 Messages.getQuestionIcon()) != Messages.YES) return
    DbConnectionService.getInstance(project).disconnect(ds.id)
    DbMetadataCache.getInstance(project).invalidate(ds.id)
    DbDataSourceService.getInstance(project).remove(ds.id)
  }

  private fun openConsole(dataSource: DbDataSource, statement: String?) {
    val file = DbConsoles.openConsole(project, dataSource, statement) ?: return
    if (statement != null) DbRunner.start(project, file, dataSource, listOf(statement.removeSuffix(";")))
  }

  fun selectAll(node: DbNode.Table): String {
    val ref = node.ref
    val quote = if (node.dataSource.dbms.usesCatalogs) '`' else '"'
    fun name(s: String) = if (s.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && !SqlTokens.isKeyword(s)) s else "$quote$s$quote"
    val qualifier = (if (node.dataSource.dbms.usesCatalogs) ref.catalog else ref.schema)?.let { name(it) + "." }.orEmpty()
    return "SELECT * FROM $qualifier${name(ref.name)};"
  }

  private fun selectedNode(): DbNode? = (tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? DbNode

  private fun selectedSource(): DbDataSource? {
    var node = tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode
    while (node != null) {
      (node.userObject as? DbNode.Source)?.let { return it.dataSource }
      node = node.parent as? DefaultMutableTreeNode
    }
    return null
  }

  private fun expandable(payload: DbNode) = DefaultMutableTreeNode(payload, true).apply { add(DefaultMutableTreeNode(DbNode.Loading, false)) }

  private fun DefaultMutableTreeNode.firstOrNull(): DefaultMutableTreeNode? = if (childCount > 0) getChildAt(0) as DefaultMutableTreeNode else null

  private fun DefaultMutableTreeNode.getClientLoading() = this in loading
  private fun DefaultMutableTreeNode.setClientLoading(on: Boolean) {
    if (on) loading.add(this) else loading.remove(this)
  }

  private fun action(key: String, icon: javax.swing.Icon, enabled: () -> Boolean = { true }, perform: () -> Unit): AnAction =
    object : DumbAwareAction(PhantomDbBundle.message(key), null, icon) {
      override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
      override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = enabled()
      }

      override fun actionPerformed(e: AnActionEvent) = perform()
    }

  private class Renderer : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
      when (val node = (value as? DefaultMutableTreeNode)?.userObject as? DbNode) {
        is DbNode.Source -> {
          icon = node.dataSource.dbms.icon
          append(node.dataSource.name)
          append("  " + node.dataSource.url.removePrefix("jdbc:"), SimpleTextAttributes.GRAYED_ATTRIBUTES)
          if (node.dataSource.readOnly) append("  " + PhantomDbBundle.message("db.tree.read.only"), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
        }
        is DbNode.Schema -> {
          icon = AllIcons.Nodes.DataSchema
          append(node.ref.name)
        }
        is DbNode.Table -> {
          icon = AllIcons.Nodes.DataTables
          append(node.ref.name)
          if (node.ref.isView) append("  " + node.ref.kind.lowercase(), SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
        is DbNode.Column -> {
          icon = AllIcons.Nodes.DataColumn
          append(node.info.name, if (node.info.primaryKey) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
          val extra = listOfNotNull(node.info.typeName.ifEmpty { null }, if (node.info.primaryKey) "PK" else null,
                                    if (!node.info.nullable && !node.info.primaryKey) "NOT NULL" else null)
          append("  " + extra.joinToString(" · "), SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
        DbNode.Loading -> append(PhantomDbBundle.message("db.tree.loading"), SimpleTextAttributes.GRAYED_ATTRIBUTES)
        is DbNode.Message -> append(node.text, if (node.error) SimpleTextAttributes.ERROR_ATTRIBUTES else SimpleTextAttributes.GRAYED_ATTRIBUTES)
        null -> {}
      }
    }
  }
}
