// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.ide.CopyPasteManager
import java.awt.datatransfer.StringSelection
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.TreeSpeedSearch
import com.intellij.util.Alarm
import java.awt.BorderLayout
import java.awt.event.KeyEvent
import javax.swing.KeyStroke
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import dev.phantom.db.core.dialect
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
import dev.phantom.db.core.PhantomDbIcons
import dev.phantom.db.core.exec.*
import kotlinx.coroutines.CancellationException
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
    fun show(project: Project, focus: Boolean = true) {
      val manager = ToolWindowManager.getInstance(project)
      val toolWindow = manager.getToolWindow(ID) ?: manager.registerToolWindow(
        RegisterToolWindowTask(ID, ToolWindowAnchor.RIGHT, contentFactory = DbToolWindowFactory(), icon = AllIcons.Nodes.DataTables,
                               stripeTitle = Supplier { PhantomDbBundle.message("toolwindow.stripe.Database") }))
      if (focus) toolWindow.activate(null) else toolWindow.show()
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
  /** A data source folder; [path] is the whole group, `a/b`, its last part the name shown. */
  class Group(val path: String) : DbNode
  class Schema(val dataSource: DbDataSource, val ref: DbSchemaRef) : DbNode
  class Table(val dataSource: DbDataSource, val ref: DbTableRef) : DbNode
  /** [foreignKey]: the column is part of a foreign key of its table. */
  class Column(val info: DbColumnInfo, val foreignKey: Boolean = false) : DbNode
  /**
   * Object groups as in DataGrip, each loaded when expanded: Tables, Views, Materialized Views, Routines, Triggers and
   * Sequences under a schema; Keys, Foreign Keys, Indexes and Triggers under a table; Consoles under a data source.
   */
  class Folder(val dataSource: DbDataSource, val kind: Kind, val table: DbTableRef?, val schema: DbSchemaRef?) : DbNode {
    enum class Kind(val key: String) {
      TABLES("db.tree.tables"), VIEWS("db.tree.views"), MATERIALIZED_VIEWS("db.tree.materialized.views"), ROUTINES("db.tree.routines"),
      TRIGGERS("db.tree.triggers"), SEQUENCES("db.tree.sequences"),
      KEYS("db.tree.keys"), FOREIGN_KEYS("db.tree.foreign.keys"), INDEXES("db.tree.indexes"), TABLE_TRIGGERS("db.tree.triggers"),
      CONSOLES("db.tree.consoles")
    }
  }
  class Key(val key: DbPrimaryKey) : DbNode
  class ForeignKey(val dataSource: DbDataSource, val fk: DbForeignKey) : DbNode
  class Index(val index: DbIndex) : DbNode
  class Routine(val dataSource: DbDataSource, val routine: DbRoutine) : DbNode
  class Trigger(val dataSource: DbDataSource, val trigger: DbTrigger) : DbNode
  class Sequence(val dataSource: DbDataSource, val sequence: DbSequence) : DbNode
  class Console(val dataSource: DbDataSource, val file: VirtualFile) : DbNode
  data object Loading : DbNode
  class Message(val text: String, val error: Boolean) : DbNode

  companion object {
    /** The name the tree filter matches: database objects and consoles have one; data sources, schemas and folders do not. */
    fun objectName(node: DbNode?): String? = when (node) {
      is Table -> node.ref.name
      is Routine -> node.routine.name
      is Trigger -> node.trigger.name
      is Sequence -> node.sequence.name
      is Console -> node.file.nameWithoutExtension
      else -> null
    }

    /** What speed search matches: the name each node shows first. */
    fun text(node: DbNode?): String = objectName(node) ?: when (node) {
      is Source -> node.dataSource.name
      is Group -> node.path.substringAfterLast('/')
      is Schema -> node.ref.name
      is Column -> node.info.name
      is Folder -> PhantomDbBundle.message(node.kind.key)
      is Key -> node.key.name.orEmpty()
      is ForeignKey -> node.fk.name.orEmpty()
      is Index -> node.index.name
      else -> ""
    }
  }
}

/**
 * Data sources → schemas → object folders → objects → columns and table folders. Children are loaded when a node is
 * first expanded, on the schema-tree connection, so a long console query does not block browsing. The filter field
 * narrows what is loaded to objects whose name contains its text and hides folders left empty.
 */
class DbExplorerPanel(private val project: Project, parent: Disposable) : SimpleToolWindowPanel(true, true) {
  private val root = DefaultMutableTreeNode()
  private val model = DefaultTreeModel(root)
  val tree: Tree = object : Tree(model) {
    /** Rows of a colored data source, its own and all below it, get its tint. */
    override fun getPathBackground(path: TreePath, row: Int): java.awt.Color? {
      val source = path.path.firstNotNullOfOrNull { ((it as? DefaultMutableTreeNode)?.userObject as? DbNode.Source)?.dataSource }
      return DbColors.tint(source?.color) ?: super.getPathBackground(path, row)
    }
  }
  /** Nodes being loaded, each with a token of its load: a reload starts a new one, and the old load's result is dropped. */
  private val loading = HashMap<DefaultMutableTreeNode, Any>()
  /** All children of each loaded node; the tree holds the ones the filter lets through. */
  private val loaded = HashMap<DefaultMutableTreeNode, List<DefaultMutableTreeNode>>()
  private val filterField = SearchTextField(false)
  private val filterAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parent)
  /** Lower-cased filter text; empty shows everything. */
  private var filter = ""
  private var expandedBeforeFilter: List<TreePath> = emptyList()
  /** The tree commands by action id; filled while the toolbar is built in init, so declared before it. */
  private val commands = HashMap<String, Command>()

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
    TreeSpeedSearch.installOn(tree, false) { path -> DbNode.text((path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? DbNode) }
    object : DoubleClickListener() {
      override fun onDoubleClick(event: MouseEvent): Boolean = open(selectedNode())
    }.installOn(tree)
    object : DumbAwareAction() {
      override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
      // on a folder, Enter keeps expanding and collapsing
      override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = opens(selectedNode())
      }

      override fun actionPerformed(e: AnActionEvent) {
        open(selectedNode())
      }
    }.registerCustomShortcutSet(CustomShortcutSet(*ActionManager.getInstance().getAction(IdeActions.ACTION_EDIT_SOURCE).shortcutSet.shortcuts,
                                                  KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), null)), tree, parent)

    val group = DefaultActionGroup(
      command("Add") { add() },
      command("Edit", { selectedSource() != null }) { edit() },
      command("Remove", { selectedNode() is DbNode.Source }) { remove() },
      command("Disconnect", { selectedSource()?.let { DbConnectionService.getInstance(project).isConnected(it.id) } == true }) {
        selectedSource()?.let { ds ->
          if (DbTransactions.endOrKeep(project, ds, PhantomDbBundle.message("action.PhantomDb.Tree.Disconnect.text"))) {
            DbConnectionService.getInstance(project).disconnect(ds.id)
          }
        }
      },
      command("Sessions") { DbSessionsDialog(project).show() },
      command("Duplicate", { selectedNode() is DbNode.Source }) { (selectedNode() as? DbNode.Source)?.let { duplicate(it.dataSource) } },
      command("CopySource", { selectedNode() is DbNode.Source }) {
        (selectedNode() as? DbNode.Source)?.let { CopyPasteManager.getInstance().setContents(StringSelection(DbDataSourceClipboard.write(listOf(it.dataSource)))) }
      },
      command("PasteSource", { DbDataSourceClipboard.read(clipboardText()).isNotEmpty() }) { paste() },
      command("MoveToGroup", { selectedNode() is DbNode.Source || selectedNode() is DbNode.Group }) { moveToGroup() },
      command("MakeGlobal", { selectedNode() is DbNode.Source }) {
        (selectedNode() as? DbNode.Source)?.dataSource?.let { ds -> DbDataSourceService.getInstance(project).save(ds.copy().apply { global = !ds.global }, null) }
      },
      Separator.getInstance(),
      command("Refresh") { refresh() },
      command("Drivers") { DbDriversDialog(project, selectedSource()?.let { it.driverId ?: it.driver()?.id }).show() },
      command("OpenConsole", { selectedSource() != null }) { selectedSource()?.let { openConsole(it, null) } },
      ActionManager.getInstance().getAction("PhantomDb.NewConsole"),
      ActionManager.getInstance().getAction("PhantomDb.ShowHistory"),
      command("Data", { selectedNode() is DbNode.Table }) {
        (selectedNode() as? DbNode.Table)?.let { DbTableDataView.open(project, it.dataSource, it.ref) }
      },
      command("Ddl", { ddlTarget() != null }) { ddlTarget()?.let { showDdl(project, it.first, it.second) } },
      command("CopyDdl", { ddlTarget() != null }) { ddlTarget()?.let { copyDdl(project, it.first, it.second) } },
      command("SqlGenerator", { (selectedNode() as? DbNode.Table)?.dataSource?.dialect()?.hasDialect == true }) {
        (selectedNode() as? DbNode.Table)?.let { sqlGenerator(project, it.dataSource, it.ref) }
      },
      command("Modify", {
        (selectedNode() as? DbNode.Table)?.let { !it.ref.isView && !it.dataSource.readOnly && it.dataSource.dialect().hasDialect } == true
      }) { modifyTable() },
      command("Select", { selectedNode() is DbNode.Table }) {
        (selectedNode() as? DbNode.Table)?.let { openConsole(it.dataSource, selectAll(it)) }
      },
      command("ImportCsv", {
        (selectedNode() as? DbNode.Table)?.let { !it.ref.isView && !it.dataSource.readOnly } == true
      }) {
        (selectedNode() as? DbNode.Table)?.let { DbCsvImportDialog.start(project, it.dataSource, it.ref) }
      },
      command("Diagram", { diagramTarget() != null }) {
        diagramTarget()?.let { (ds, schema, tables) -> DbDiagrams.open(project, ds, schema, tables) }
      },
      command("Entity", { selectedNode() is DbNode.Table }) {
        (selectedNode() as? DbNode.Table)?.let { DbEntityGenerator.start(project, it.dataSource, it.ref) }
      },
      command("Compare", { (selectedNode() as? DbNode.Schema)?.dataSource?.dialect()?.hasDialect == true }) {
        (selectedNode() as? DbNode.Schema)?.let { DbSchemaCompare.chooseAndCompare(project, DbSchemaSide(it.dataSource, it.ref), tree) }
      },
      Separator.getInstance(),
      object : ToggleAction(PhantomDbBundle.message("db.action.system.schemas.text"), null, AllIcons.Actions.ToggleVisibility), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent): Boolean = showSystemSchemas(project)
        override fun setSelected(e: AnActionEvent, state: Boolean) {
          PropertiesComponent.getInstance(project).setValue(SYSTEM_SCHEMAS_KEY, state)
          reload()
        }
      },
    )
    val toolbar = ActionManager.getInstance().createActionToolbar("PhantomDbExplorer", group, true)
    toolbar.targetComponent = tree
    filterField.textEditor.emptyText.text = PhantomDbBundle.message("db.tree.filter")
    filterField.addDocumentListener(object : DocumentAdapter() {
      override fun textChanged(e: DocumentEvent) {
        filterAlarm.cancelAllRequests()
        filterAlarm.addRequest({ setFilter(filterField.text) }, 200)
      }
    })
    setToolbar(JPanel(BorderLayout()).apply {
      add(toolbar.component, BorderLayout.NORTH)
      add(filterField, BorderLayout.SOUTH)
    })
    setContent(ScrollPaneFactory.createScrollPane(tree, true))
    PopupHandler.installPopupMenu(tree, group, "PhantomDbExplorerPopup")

    // the green dot of a connected data source
    DbConnectionService.getInstance(project).addSessionListener(parent) {
      com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({ tree.repaint() }, project.disposed)
    }
    DbDataSourceService.getInstance(project).addListener(parent) {
      com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({ reload() }, project.disposed)
    }
    reload()
  }

  /** Rebuilds the data source level; expanded data sources stay expanded and reload their children. */
  fun reload() {
    val expandedObjects = TreeUtil.collectExpandedUserObjects(tree)
    val expanded = expandedObjects.filterIsInstance<DbNode.Source>().map { it.dataSource.id }.toSet()
    val expandedGroups = expandedObjects.filterIsInstance<DbNode.Group>().map { it.path }.toSet()
    root.removeAllChildren()
    loaded.clear()
    loading.clear()
    val groups = HashMap<String, DefaultMutableTreeNode>()
    fun group(path: String): DefaultMutableTreeNode {
      if (path.isEmpty()) return root
      return groups.getOrPut(path) {
        val parent = group(path.substringBeforeLast('/', ""))
        DefaultMutableTreeNode(DbNode.Group(path), true).also { parent.add(it) }
      }
    }
    val sources = DbDataSourceService.getInstance(project).dataSources
    // folders first, as in DataGrip
    for (ds in sources) if (groupOf(ds).isNotEmpty()) group(groupOf(ds))
    for (ds in sources) group(groupOf(ds)).add(expandable(DbNode.Source(ds)))
    // the filter re-adds a group's children from here
    for (node in groups.values) loaded[node] = node.children().toList().map { it as DefaultMutableTreeNode }
    model.reload()
    for (node in groups.values.sortedBy { it.level }) if ((node.userObject as DbNode.Group).path in expandedGroups) tree.expandPath(TreePath(node.path))
    for (node in sourceNodes()) {
      if ((node.userObject as DbNode.Source).dataSource.id in expanded) tree.expandPath(TreePath(node.path))
    }
  }

  /** The data source nodes, at the top or in their groups. */
  internal fun sourceNodes(): List<DefaultMutableTreeNode> =
    TreeUtil.treeNodeTraverser(root).filter(DefaultMutableTreeNode::class.java).filter { it.userObject is DbNode.Source }.toList()

  private fun groupOf(ds: DbDataSource): String = ds.group.split('/').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("/")

  private fun load(node: DefaultMutableTreeNode) {
    val placeholder = (node.firstOrNull())?.userObject
    if (placeholder != DbNode.Loading || node in loading) return
    val payload = node.userObject as? DbNode ?: return
    val token = Any()
    loading[node] = token
    val systemSchemas = showSystemSchemas(project)
    DbConnectionService.getInstance(project).launch {
      val children: List<DbNode> = try {
        children(payload, systemSchemas).ifEmpty { listOf(nothing()) }
      }
      catch (e: CancellationException) {
        throw e
      }
      catch (e: SQLException) {
        listOf(DbNode.Message(DbExecutor.message(e), true))
      }
      catch (e: DbException) {
        listOf(DbNode.Message(e.message.orEmpty(), true))
      }
      catch (e: Exception) {
        // a driver that does not implement a metadata call: show it rather than "Loading…" forever
        listOf(DbNode.Message(e.message ?: e.javaClass.simpleName, true))
      }
      catch (e: LinkageError) {
        listOf(DbNode.Message(e.message ?: e.javaClass.simpleName, true))
      }
      // the consoles need no connection: they show even when the data source cannot connect
      val all = if (payload is DbNode.Source) children + DbNode.Folder(payload.dataSource, DbNode.Folder.Kind.CONSOLES, null, null) else children
      withContext(Dispatchers.EDT) {
        if (loading[node] !== token) return@withContext  // reloaded meanwhile
        loading.remove(node)
        loaded[node] = all.map { child ->
          val leaf = child !is DbNode.Source && child !is DbNode.Schema && child !is DbNode.Table && child !is DbNode.Folder
          if (leaf) DefaultMutableTreeNode(child, false) else expandable(child)
        }
        applyFilter(node, filter.isNotEmpty() && generateSequence(node) { it.parent as? DefaultMutableTreeNode }.none { DbNode.objectName(it.userObject as? DbNode) != null })
        model.nodeStructureChanged(node)
        // a data source with a single schema opens straight into it
        if (payload is DbNode.Source && all.count { it is DbNode.Schema } == 1 && node.childCount > 0) {
          (node.firstChild as DefaultMutableTreeNode).takeIf { it.userObject is DbNode.Schema }?.let { tree.expandPath(TreePath(it.path)) }
        }
      }
    }
  }

  /** Children of [payload]; may connect (on the schema-tree connection), except for consoles. */
  private suspend fun children(payload: DbNode, systemSchemas: Boolean): List<DbNode> {
    val cache = DbMetadataCache.getInstance(project)
    val objects = DbObjectCache.getInstance(project)
    return when (payload) {
      is DbNode.Source -> cache.schemas(payload.dataSource, systemSchemas).map { DbNode.Schema(payload.dataSource, it) }
      is DbNode.Schema -> {
        val tables = cache.tables(payload.dataSource, payload.ref)
        fun folder(kind: DbNode.Folder.Kind) = DbNode.Folder(payload.dataSource, kind, null, payload.ref)
        listOfNotNull(folder(DbNode.Folder.Kind.TABLES),
                      folder(DbNode.Folder.Kind.VIEWS).takeIf { tables.any { it.isView && !it.isMaterializedView } },
                      folder(DbNode.Folder.Kind.MATERIALIZED_VIEWS).takeIf { tables.any { it.isMaterializedView } },
                      folder(DbNode.Folder.Kind.ROUTINES), folder(DbNode.Folder.Kind.TRIGGERS), folder(DbNode.Folder.Kind.SEQUENCES))
      }
      is DbNode.Table -> {
        val ds = payload.dataSource
        val fkColumns = if (payload.ref.isView) emptySet() else foreignKeyColumns(ds, payload.ref)
        cache.columns(ds, payload.ref).map { DbNode.Column(it, it.name in fkColumns) } +
        if (payload.ref.isView) emptyList()
        else listOf(DbNode.Folder.Kind.KEYS, DbNode.Folder.Kind.FOREIGN_KEYS, DbNode.Folder.Kind.INDEXES, DbNode.Folder.Kind.TABLE_TRIGGERS)
          .map { DbNode.Folder(ds, it, payload.ref, DbSchemaRef(ds.id, payload.ref.catalog, payload.ref.schema)) }
      }
      is DbNode.Folder -> {
        val ds = payload.dataSource
        when (payload.kind) {
          DbNode.Folder.Kind.TABLES -> cache.tables(ds, payload.schema!!).filter { !it.isView }.map { DbNode.Table(ds, it) }
          DbNode.Folder.Kind.VIEWS -> cache.tables(ds, payload.schema!!).filter { it.isView && !it.isMaterializedView }.map { DbNode.Table(ds, it) }
          DbNode.Folder.Kind.MATERIALIZED_VIEWS -> cache.tables(ds, payload.schema!!).filter { it.isMaterializedView }.map { DbNode.Table(ds, it) }
          DbNode.Folder.Kind.ROUTINES -> objects.routines(ds, payload.schema!!).map { DbNode.Routine(ds, it) }
          DbNode.Folder.Kind.TRIGGERS -> objects.triggers(ds, payload.schema!!).map { DbNode.Trigger(ds, it) }
          DbNode.Folder.Kind.SEQUENCES -> objects.sequences(ds, payload.schema!!).map { DbNode.Sequence(ds, it) }
          DbNode.Folder.Kind.KEYS -> objects.primaryKey(ds, payload.table!!).takeIf { it.columns.isNotEmpty() }?.let { listOf(DbNode.Key(it)) }.orEmpty()
          DbNode.Folder.Kind.FOREIGN_KEYS -> objects.foreignKeys(ds, payload.table!!).map { DbNode.ForeignKey(ds, it) }
          DbNode.Folder.Kind.INDEXES -> objects.indexes(ds, payload.table!!).map { DbNode.Index(it) }
          DbNode.Folder.Kind.TABLE_TRIGGERS -> objects.triggers(ds, payload.schema!!).filter { it.table.equals(payload.table!!.name, ignoreCase = true) }
            .map { DbNode.Trigger(ds, it) }
          DbNode.Folder.Kind.CONSOLES -> DbConsoles.consoles(ds.id).map { DbNode.Console(ds, it) }
        }
      }
      else -> emptyList()
    }
  }

  /** Columns in a foreign key, for the FK marks; none when the driver cannot tell (the columns still show). */
  private suspend fun foreignKeyColumns(dataSource: DbDataSource, table: DbTableRef): Set<String> = try {
    foreignKeys(dataSource, table).flatMap { it.columns }.toSet()
  }
  catch (e: CancellationException) {
    throw e
  }
  catch (_: Throwable) {
    emptySet()
  }

  /** How table expansion finds foreign keys; tests replace it with a failing one. */
  internal var foreignKeys: suspend (DbDataSource, DbTableRef) -> List<DbForeignKey> =
    { ds, table -> DbObjectCache.getInstance(project).foreignKeys(ds, table) }

  private fun nothing() = DbNode.Message(PhantomDbBundle.message("db.tree.nothing"), false)

  /** Shows only loaded objects whose name contains [text] (any case), and the folders and schemas that hold them. */
  fun setFilter(text: String) {
    val value = text.trim().lowercase()
    if (value == filter) return
    if (filter.isEmpty()) expandedBeforeFilter = TreeUtil.collectExpandedPaths(tree)
    filter = value
    if (filterField.text.trim().lowercase() != value) filterField.text = text
    val selected = tree.selectionPaths
    val expanded = TreeUtil.collectExpandedPaths(tree)
    for (i in 0 until root.childCount) applyFilter(root.getChildAt(i) as DefaultMutableTreeNode, filter.isNotEmpty())
    model.reload()
    // what was open and still shows stays open (a matching table's columns)
    fun attached(path: TreePath) = (path.lastPathComponent as DefaultMutableTreeNode).root == root
    fun isObject(path: TreePath) = DbNode.objectName((path.lastPathComponent as DefaultMutableTreeNode).userObject as? DbNode) != null
    (if (filter.isEmpty()) expandedBeforeFilter + expanded.filter(::isObject) else expanded).filter(::attached)
      .forEach { tree.expandPath(TreePath((it.lastPathComponent as DefaultMutableTreeNode).path)) }
    if (filter.isNotEmpty()) TreeUtil.treeNodeTraverser(root).filter { it is DefaultMutableTreeNode && it.childCount > 0 && it in loaded && DbNode.objectName(it.userObject as? DbNode) == null }
      .forEach { tree.expandPath(TreePath((it as DefaultMutableTreeNode).path)) }
    selected?.filter { p -> (p.lastPathComponent as DefaultMutableTreeNode).let { it.root == root } }?.let { tree.selectionPaths = it.toTypedArray() }
  }

  /**
   * Puts the children of [node] the filter lets through under it, all the way down, and tells whether [node] shows.
   * [active]: the filter applies here; below a matching object (a table's columns and keys) everything shows.
   */
  private fun applyFilter(node: DefaultMutableTreeNode, active: Boolean): Boolean {
    val payload = node.userObject as? DbNode
    val name = DbNode.objectName(payload)
    val kids = loaded[node]
    val visible = kids?.filter { applyFilter(it, active && name == null) }
    if (kids != null) {
      node.removeAllChildren()
      visible!!.forEach(node::add)
    }
    if (!active) return true
    return when {
      payload is DbNode.Source -> true
      payload is DbNode.Group -> !visible.isNullOrEmpty()
      name != null -> name.lowercase().contains(filter)
      payload is DbNode.Schema || payload is DbNode.Folder -> !visible.isNullOrEmpty()
      else -> false
    }
  }

  private fun ddlTarget(): Pair<DbDataSource, DbDdl.Target>? = when (val node = selectedNode()) {
    is DbNode.Table -> node.dataSource to DbDdl.Target.Table(node.ref)
    is DbNode.Routine -> node.dataSource to DbDdl.Target.Routine(node.routine)
    is DbNode.Trigger -> node.dataSource to DbDdl.Target.Trigger(node.trigger)
    else -> null
  }?.takeIf { it.first.dialect().hasDialect }

  /**
   * Double-click, Enter or F4: a table opens its data; a view, routine or trigger its DDL; a foreign key selects the
   * table it references; a console opens. Double-click on a data source opens its console (Enter there expands it).
   */
  private fun opens(node: DbNode?): Boolean = when (node) {
    is DbNode.Table, is DbNode.ForeignKey, is DbNode.Console -> true
    is DbNode.Routine -> node.dataSource.dialect().hasDialect
    is DbNode.Trigger -> node.dataSource.dialect().hasDialect
    else -> false
  }

  private fun open(node: DbNode?): Boolean {
    when (node) {
      is DbNode.Source -> openConsole(node.dataSource, null)
      is DbNode.Table -> openObject(project, node.dataSource, node.ref)
      is DbNode.Routine -> if (node.dataSource.dialect().hasDialect) showDdl(project, node.dataSource, DbDdl.Target.Routine(node.routine)) else return false
      is DbNode.Trigger -> if (node.dataSource.dialect().hasDialect) showDdl(project, node.dataSource, DbDdl.Target.Trigger(node.trigger)) else return false
      is DbNode.ForeignKey -> DbTreeNavigation.select(project, node.dataSource, node.fk.refTable, null)
      is DbNode.Console -> FileEditorManager.getInstance(project).openFile(node.file, true)
      else -> return false
    }
    return true
  }

  private fun modifyTable() {
    val path = tree.selectionPath ?: return
    val node = (selectedNode() as? DbNode.Table) ?: return
    DbModifyTableDialog.show(project, node.dataSource, node.ref) {
      DbMetadataCache.getInstance(project).invalidate(node.dataSource.id)
      reloadChildren(path.lastPathComponent as DefaultMutableTreeNode)
    }
  }

  /** Drops a loaded node's children and loads them again if it is expanded. */
  fun reloadChildren(node: DefaultMutableTreeNode) {
    val expanded = tree.isExpanded(TreePath(node.path))
    forget(node)
    node.removeAllChildren()
    node.add(DefaultMutableTreeNode(DbNode.Loading, false))
    model.nodeStructureChanged(node)
    if (expanded) {
      tree.expandPath(TreePath(node.path))
      load(node)
    }
  }

  private fun forget(node: DefaultMutableTreeNode) {
    loading.remove(node)
    loaded.remove(node)?.forEach(::forget)
  }

  /** Lists the data source's consoles again where its Consoles folder is loaded. */
  fun consolesChanged(dataSourceId: String) {
    for (source in sourceNodes()) {
      if ((source.userObject as? DbNode.Source)?.dataSource?.id != dataSourceId) continue
      loaded[source]?.firstOrNull { (it.userObject as? DbNode.Folder)?.kind == DbNode.Folder.Kind.CONSOLES }?.takeIf { it in loaded }?.let(::reloadChildren)
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

  /** A copy with a new id, " (copy)" in its name and no saved password (asked at the first connect). */
  private fun duplicate(ds: DbDataSource) {
    val copy = ds.copy().apply {
      id = java.util.UUID.randomUUID().toString()
      name = PhantomDbBundle.message("db.datasource.copy.name", ds.name)
    }
    DbDataSourceService.getInstance(project).save(copy, null)
  }

  private fun clipboardText(): String? =
    runCatching { CopyPasteManager.getInstance().getContents<String>(java.awt.datatransfer.DataFlavor.stringFlavor) }.getOrNull()

  /** Data sources copied (here or in another project or IDE) are added with new ids, in the selected group; passwords stay behind. */
  private fun paste() {
    val group = (selectedNode() as? DbNode.Group)?.path ?: selectedSource()?.group
    val service = DbDataSourceService.getInstance(project)
    for (ds in DbDataSourceClipboard.read(clipboardText())) {
      ds.id = java.util.UUID.randomUUID().toString()
      if (group != null) ds.group = group
      service.save(ds, null)
    }
  }

  /** Move to Group (F6, as in DataGrip): a data source into a folder (`a/b` nests, empty takes it out); on a folder, renames it. */
  private fun moveToGroup() {
    val service = DbDataSourceService.getInstance(project)
    when (val node = selectedNode()) {
      is DbNode.Source -> {
        val group = Messages.showInputDialog(project, PhantomDbBundle.message("db.group.prompt"), PhantomDbBundle.message("db.group.title"), null,
                                             node.dataSource.group, null) ?: return
        service.save(node.dataSource.copy().apply { this.group = group.trim() }, null)
      }
      is DbNode.Group -> {
        val renamed = Messages.showInputDialog(project, PhantomDbBundle.message("db.group.prompt"), PhantomDbBundle.message("db.group.rename"), null,
                                               node.path, null)?.trim() ?: return
        for (ds in service.dataSources) {
          val g = ds.group.trim()
          if (g == node.path || g.startsWith(node.path + "/")) service.save(ds.copy().apply { group = renamed + g.removePrefix(node.path) }, null)
        }
      }
      else -> {}
    }
  }

  private fun edit() {
    val ds = selectedSource() ?: return
    DbDataSourceDialog.edit(project, ds)
  }

  private fun remove() {
    val ds = selectedSource() ?: return
    if (Messages.showYesNoDialog(project, PhantomDbBundle.message("db.remove.confirm", ds.name), PhantomDbBundle.message("db.action.remove.text"),
                                 Messages.getQuestionIcon()) != Messages.YES) return
    if (!DbTransactions.endOrKeep(project, ds, PhantomDbBundle.message("db.action.remove.text"))) return
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
    val quote = if (node.dataSource.dialect().usesCatalogs) '`' else '"'
    fun name(s: String) = if (s.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && !SqlTokens.isKeyword(s)) s else "$quote$s$quote"
    val qualifier = (if (node.dataSource.dialect().usesCatalogs) ref.catalog else ref.schema)?.let { name(it) + "." }.orEmpty()
    return "SELECT * FROM $qualifier${name(ref.name)};"
  }

  private fun selectedNode(): DbNode? = (tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? DbNode

  /** A diagram of the selected schema, or of the selected tables of one data source (null: all of their schema). */
  internal fun diagramTarget(): Triple<DbDataSource, DbSchemaRef, List<DbTableRef>?>? {
    val nodes = tree.selectionPaths.orEmpty().mapNotNull { (it.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? DbNode }
    val tables = nodes.filterIsInstance<DbNode.Table>()
    if (tables.isNotEmpty() && tables.size == nodes.size) {
      val ds = tables.first().dataSource
      if (tables.any { it.dataSource.id != ds.id }) return null
      val ref = tables.first().ref
      return Triple(ds, DbSchemaRef(ds.id, ref.catalog, ref.schema), tables.map { it.ref })
    }
    val schema = nodes.singleOrNull() as? DbNode.Schema ?: return null
    return Triple(schema.dataSource, schema.ref, null)
  }

  /** The data source of the selected node or one of its parents. */
  fun selectedSource(): DbDataSource? = selectedAncestor<DbNode.Source>()?.dataSource

  /** The schema of the selected node or one of its parents. */
  fun selectedSchema(): DbSchemaRef? = selectedAncestor<DbNode.Schema>()?.ref
    ?: (selectedNode() as? DbNode.Table)?.ref?.let { DbSchemaRef(it.dataSourceId, it.catalog, it.schema) }

  private inline fun <reified T : DbNode> selectedAncestor(): T? {
    var node = tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode
    while (node != null) {
      (node.userObject as? T)?.let { return it }
      node = node.parent as? DefaultMutableTreeNode
    }
    return null
  }

  private fun expandable(payload: DbNode) = DefaultMutableTreeNode(payload, true).apply { add(DefaultMutableTreeNode(DbNode.Loading, false)) }

  private fun DefaultMutableTreeNode.firstOrNull(): DefaultMutableTreeNode? = if (childCount > 0) getChildAt(0) as DefaultMutableTreeNode else null

  /** What the registered `PhantomDb.Tree.<name>` action does here; the action itself (text, icon, shortcut) is in the plugin XML. */
  internal class Command(val enabled: () -> Boolean, val perform: () -> Unit)

  internal fun command(id: String): Command? = commands[id]

  private fun command(name: String, enabled: () -> Boolean = { true }, perform: () -> Unit): AnAction {
    val id = DbTreeAction.PREFIX + name
    commands[id] = Command(enabled, perform)
    return ActionManager.getInstance().getAction(id)
  }

  override fun uiDataSnapshot(sink: DataSink) {
    super.uiDataSnapshot(sink)
    sink[KEY] = this
  }

  companion object {
    val KEY: DataKey<DbExplorerPanel> = DataKey.create("PhantomDb.ExplorerPanel")
    private const val SYSTEM_SCHEMAS_KEY = "phantom.db.tree.systemSchemas"

    fun showSystemSchemas(project: Project): Boolean = PropertiesComponent.getInstance(project).getBoolean(SYSTEM_SCHEMAS_KEY, false)

    /** The panel in the Database tool window, if it was created. */
    fun find(project: Project): DbExplorerPanel? =
      ToolWindowManager.getInstance(project).getToolWindow(DbToolWindowFactory.ID)?.contentManager?.contents?.firstNotNullOfOrNull { it.component as? DbExplorerPanel }

    /** A table opens its data; a view its DDL where the dialect reports it, else its data. */
    fun openObject(project: Project, dataSource: DbDataSource, ref: DbTableRef) {
      if (ref.isView && dataSource.dialect().hasDialect) showDdl(project, dataSource, DbDdl.Target.Table(ref))
      else DbTableDataView.open(project, dataSource, ref)
    }

    /** Copies the DDL of a table, view, routine or trigger (Copy DDL, as in DataGrip). */
    fun copyDdl(project: Project, dataSource: DbDataSource, target: DbDdl.Target) {
      DbConnectionService.getInstance(project).launch {
        val text = ddlText(project, dataSource, target)
        withContext(Dispatchers.EDT) {
          com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(text))
          com.intellij.openapi.wm.WindowManager.getInstance().getStatusBar(project)?.info = PhantomDbBundle.message("db.ddl.copied")
        }
      }
    }

    /** Opens the table's DDL with a SELECT, INSERT, UPDATE and DELETE of its columns in a read-only SQL tab. */
    fun sqlGenerator(project: Project, dataSource: DbDataSource, ref: DbTableRef) {
      DbConnectionService.getInstance(project).launch {
        val ddl = ddlText(project, dataSource, DbDdl.Target.Table(ref))
        val columns = try {
          DbMetadataCache.getInstance(project).columns(dataSource, ref)
        }
        catch (e: SQLException) {
          emptyList()
        }
        val text = DbSqlGenerator.text(dataSource.dialect(), ref, columns, ddl)
        withContext(Dispatchers.EDT) {
          val file = com.intellij.testFramework.LightVirtualFile("${ref.name} (SQL Generator).sql", SqlFileType.INSTANCE, text).apply { isWritable = false }
          FileEditorManager.getInstance(project).openFile(file, true)
        }
      }
    }

    private suspend fun ddlText(project: Project, dataSource: DbDataSource, target: DbDdl.Target): String = try {
      DbDdl.ddl(project, dataSource, target)
    }
    catch (e: SQLException) {
      "-- " + DbExecutor.message(e)
    }
    catch (e: DbException) {
      "-- " + e.message
    }

    /** Opens the DDL of a table, view, routine or trigger in a read-only SQL tab. */
    fun showDdl(project: Project, dataSource: DbDataSource, target: DbDdl.Target) {
      val name = when (target) {
        is DbDdl.Target.Table -> target.ref.name
        is DbDdl.Target.Routine -> target.routine.name
        is DbDdl.Target.Trigger -> target.trigger.name
      }
      DbConnectionService.getInstance(project).launch {
        val text = ddlText(project, dataSource, target)
        withContext(Dispatchers.EDT) {
          val file = com.intellij.testFramework.LightVirtualFile("$name.sql", SqlFileType.INSTANCE, text).apply { isWritable = false }
          FileEditorManager.getInstance(project).openFile(file, true)
        }
      }
    }
  }

  private inner class Renderer : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
      when (val node = (value as? DefaultMutableTreeNode)?.userObject as? DbNode) {
        is DbNode.Source -> {
          val base = PhantomDbIcons.forDataSource(node.dataSource)
          icon = if (DbConnectionService.getInstance(project).isConnected(node.dataSource.id)) com.intellij.execution.runners.ExecutionUtil.getLiveIndicator(base) else base
          append(node.dataSource.name)
          append("  " + node.dataSource.url.removePrefix("jdbc:"), SimpleTextAttributes.GRAYED_ATTRIBUTES)
          if (node.dataSource.readOnly) append("  " + PhantomDbBundle.message("db.tree.read.only"), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
          if (node.dataSource.global) append("  " + PhantomDbBundle.message("db.tree.global"), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
        }
        is DbNode.Group -> {
          icon = AllIcons.Nodes.Folder
          append(node.path.substringAfterLast('/'))
        }
        is DbNode.Schema -> {
          icon = AllIcons.Nodes.DataSchema
          append(node.ref.name, if (DbMetadataCache.isSystem(node.ref)) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
        }
        is DbNode.Table -> {
          icon = AllIcons.Nodes.DataTables
          append(node.ref.name)
          if (node.ref.isView) append("  " + node.ref.kind.lowercase(), SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
        is DbNode.Column -> {
          icon = AllIcons.Nodes.DataColumn
          append(node.info.name, if (node.info.primaryKey) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
          val extra = listOfNotNull(node.info.typeName.ifEmpty { null }, if (node.info.primaryKey) "PK" else null, if (node.foreignKey) "FK" else null,
                                    if (!node.info.nullable && !node.info.primaryKey) "NOT NULL" else null)
          append("  " + extra.joinToString(" · "), SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
        is DbNode.Folder -> {
          icon = AllIcons.Nodes.Folder
          append(PhantomDbBundle.message(node.kind.key))
        }
        is DbNode.Key -> {
          icon = AllIcons.Nodes.DataColumn
          append(node.key.name ?: "PRIMARY KEY", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
          append("  (" + node.key.columns.joinToString() + ")", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
        is DbNode.ForeignKey -> {
          icon = AllIcons.Nodes.Related
          append(node.fk.name ?: node.fk.columns.joinToString())
          append("  (" + node.fk.columns.joinToString() + ") → " + node.fk.refTable.name + " (" + node.fk.refColumns.joinToString() + ")",
                 SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
        is DbNode.Index -> {
          icon = AllIcons.Nodes.DataTables
          append(node.index.name)
          append("  (" + node.index.columns.joinToString() + ")" + if (node.index.unique) " UNIQUE" else "", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
        is DbNode.Routine -> {
          icon = if (node.routine.function) AllIcons.Nodes.Function else AllIcons.Nodes.Method
          append(node.routine.name)
          append("  " + if (node.routine.function) "function" else "procedure", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
        is DbNode.Trigger -> {
          icon = AllIcons.Nodes.Annotationtype
          append(node.trigger.name)
          append("  " + listOf(node.trigger.timing, node.trigger.event).filter { it.isNotEmpty() }.joinToString(" ").lowercase() + " · " + node.trigger.table,
                 SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
        is DbNode.Sequence -> {
          icon = AllIcons.Nodes.Constant
          append(node.sequence.name)
        }
        is DbNode.Console -> {
          icon = AllIcons.Debugger.Console
          append(node.file.nameWithoutExtension)
        }
        DbNode.Loading -> append(PhantomDbBundle.message("db.tree.loading"), SimpleTextAttributes.GRAYED_ATTRIBUTES)
        is DbNode.Message -> append(node.text, if (node.error) SimpleTextAttributes.ERROR_ATTRIBUTES else SimpleTextAttributes.GRAYED_ATTRIBUTES)
        null -> {}
      }
    }
  }
}

/**
 * A Database tree command (`PhantomDb.Tree.*`), registered so the keymap can bind it, with DataGrip's shortcuts; the
 * tree's panel says whether it applies and runs it. Outside the tree it is hidden.
 */
class DbTreeAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

  private fun command(e: AnActionEvent): DbExplorerPanel.Command? =
    panel(e.dataContext)?.command(ActionManager.getInstance().getId(this) ?: return null)

  override fun update(e: AnActionEvent) {
    val command = command(e)
    e.presentation.isVisible = command != null
    e.presentation.isEnabled = command?.enabled?.invoke() == true
    if (ActionManager.getInstance().getId(this) == PREFIX + "MakeGlobal") {
      val global = panel(e.dataContext)?.selectedSource()?.global == true
      val key = if (global) "db.action.make.project" else "db.action.make.global"
      e.presentation.text = PhantomDbBundle.message("$key.text")
      e.presentation.description = PhantomDbBundle.message("$key.description")
      e.presentation.icon = if (global) AllIcons.Nodes.Module else AllIcons.Nodes.PpWeb
      // only on a data source, so the toolbar does not carry it greyed out
      e.presentation.isVisible = e.presentation.isEnabled
    }
  }

  override fun actionPerformed(e: AnActionEvent) {
    command(e)?.perform?.invoke()
  }

  companion object {
    const val PREFIX: String = "PhantomDb.Tree."

    /** The panel whose tree the context is in; not its filter field, where Delete and the like are the text's. */
    internal fun panel(context: DataContext): DbExplorerPanel? =
      context.getData(DbExplorerPanel.KEY)?.takeIf { context.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT) === it.tree }
  }
}

/** In the Database tree its own commands win over IDE actions on the same keys (Delete, Cmd+N, Cmd+B, Cmd+D, ...). */
class DbTreeActionPromoter : ActionPromoter {
  override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction> {
    if (DbTreeAction.panel(context) == null) return emptyList()
    return actions.filterIsInstance<DbTreeAction>()
  }
}

/** Data sources on the clipboard as XML, the format they are saved in, without passwords (which never are in it). */
object DbDataSourceClipboard {
  private const val ROOT = "phantom-data-sources"

  fun write(sources: List<DbDataSource>): String {
    val element = org.jdom.Element(ROOT)
    sources.forEach { element.addContent(com.intellij.util.xmlb.XmlSerializer.serialize(it)) }
    return com.intellij.openapi.util.JDOMUtil.write(element)
  }

  /** Empty for anything that is not such XML. */
  fun read(text: String?): List<DbDataSource> {
    if (text == null || !text.trimStart().startsWith("<$ROOT")) return emptyList()
    return runCatching {
      com.intellij.openapi.util.JDOMUtil.load(text).children.mapNotNull { child ->
        runCatching { com.intellij.util.xmlb.XmlSerializer.deserialize(child, DbDataSource::class.java) }.getOrNull()
      }
    }.getOrDefault(emptyList())
  }
}
