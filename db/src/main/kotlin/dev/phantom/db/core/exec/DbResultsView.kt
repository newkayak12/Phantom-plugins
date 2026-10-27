// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import com.intellij.database.datagrid.*
import com.intellij.database.datagrid.color.GridColorModelImpl
import com.intellij.database.datagrid.color.MutationsColorLayer
import dev.phantom.db.core.dialect
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.util.Alarm
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.phantom.db.core.DbColorService
import dev.phantom.db.core.DbColors
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.DbDataSourceService
import dev.phantom.db.core.PhantomDbBundle
import dev.phantom.db.core.ui.DbFilterBar
import dev.phantom.db.core.ui.DbFilterContext
import dev.phantom.db.core.ui.DbForeignKeyActions
import dev.phantom.db.core.ui.DbTableDataView
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.ui.OnePixelSplitter
import java.awt.datatransfer.StringSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.util.function.BiConsumer
import java.util.function.Supplier
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * One tab of the results tool window: a status line ("12 rows · 8 ms") with the grid tools at its right (Transpose,
 * Value Viewer, Copy As), then the rows in the community data grid. Several statements at once get one tab per result
 * plus an Output tab. A select from one table gets WHERE and ORDER BY fields that run it again filtered.
 */
class DbResultPanel(val project: Project) : JPanel(BorderLayout()), Disposable {
  var job: Job? = null

  /** The data source the shown results came from; a single-table result from it can be edited in place. */
  var dataSource: DbDataSource? = null

  /** The schema the shown results ran in; an edited unqualified table is looked up there. */
  var schema: DbSchemaChoice? = null

  /** The table a data view shows; its grid then offers the foreign key actions. */
  var table: DbTableRef? = null

  /** Called with each new grid, so a data view can tell which grid is current. */
  var onGrid: ((DataGrid) -> Unit)? = null

  /** Set by a table's data view: Export then also offers the whole table, written in the background. */
  var exportTable: ((DbExportFormat) -> Unit)? = null

  /** Which console or file this tab shows results for; a new run from it replaces the tab unless it is pinned. */
  internal var tabKey: String? = null

  /** Grids show each record as a column (Transpose); kept for the next results. */
  var transposed: Boolean = false
    set(value) {
      field = value
      holders.forEach { it.grid.resultView.isTransposed = value }
    }

  /** The selected cell is shown in full next to the grid (Value Viewer); kept for the next results. */
  var valueViewer: Boolean = false
    set(value) {
      field = value
      holders.forEach { it.showViewer(value) }
    }

  @get:TestOnly
  var shownEditing: DbGridEdits? = null
    private set
  private var shown: Disposable? = null

  @get:TestOnly
  var shownRows: List<List<Any?>>? = null
    private set

  @get:TestOnly
  var shownStatus: String = ""
    private set

  @get:TestOnly
  val shownGrid: DataGrid? get() = currentHolder()?.grid

  @get:TestOnly
  val shownViewer: DbValueViewer? get() = currentHolder()?.viewer

  private val aggregates = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }

  @get:TestOnly
  val shownAggregates: String get() = aggregates.text.orEmpty()

  private val holders = ArrayList<GridHolder>()
  private var center: JComponent? = null

  /** The select a filter applies to, and the WHERE / ORDER BY fields for it (single-table query results only). */
  private var filterBase: String? = null

  @get:TestOnly
  var filterBar: DbFilterBar? = null
    private set

  init {
    border = JBUI.Borders.empty()
  }

  /** [keepFilter]: a filtered run of the same select keeps its WHERE / ORDER BY fields. */
  fun showRunning(preview: String, keepFilter: Boolean = false) {
    job?.cancel()
    if (!keepFilter) dropFilter()
    val top = row()
    top.add(JBLabel(PhantomDbBundle.message("db.run.running", preview), AllIcons.Process.Step_1, JBLabel.LEFT))
    top.add(ActionLink(PhantomDbBundle.message("db.run.stop")) { job?.cancel() })
    replace(top, null, "")
  }

  fun showError(message: String) {
    val top = row()
    top.add(JBLabel(firstLine(message), AllIcons.General.Error, JBLabel.LEFT).apply { foreground = JBColor.RED })
    replace(top, if (message.contains('\n') || message.length > 120) textArea(message) else null, message)
  }

  fun showOutcomes(outcomes: List<DbOutcome>) {
    val disposable = Disposer.newDisposable()
    val top = row()
    val failed = outcomes.filterIsInstance<DbOutcome.Failed>().firstOrNull()
    val status = if (outcomes.size == 1) summary(outcomes[0]) else PhantomDbBundle.message("db.run.statements", outcomes.size, outcomes.sumOf { it.elapsedMs })
    top.add(JBLabel(status, if (failed != null) AllIcons.General.Error else AllIcons.General.InspectionsOK, JBLabel.LEFT).apply {
      if (failed != null && outcomes.size == 1) foreground = JBColor.RED
    })
    if (failed != null && outcomes.size > 1) top.add(JBLabel(firstLine(failed.message)).apply { foreground = JBColor.RED })
    (outcomes.lastOrNull { it is DbOutcome.Rows } as? DbOutcome.Rows)?.takeIf { it.truncated }?.let {
      top.add(JBLabel(PhantomDbBundle.message("db.run.truncated", it.rows.size), AllIcons.General.Warning, JBLabel.LEFT).apply {
        foreground = UIUtil.getContextHelpForeground()
      })
    }
    (outcomes.lastOrNull { it is DbOutcome.Rows } as? DbOutcome.Rows)?.let { shownResult ->
      lateinit var link: ActionLink
      link = ActionLink(PhantomDbBundle.message("db.run.export")) {
        val dbms = dataSource?.dialect() ?: dev.phantom.db.core.Dbms.MYSQL
        DbExportUi.show(project, link, shownResult, dbms, insertTarget(shownResult, dbms), exportTable)
      }
      top.add(link)
    }
    top.add(JBLabel(SqlSafety.preview(outcomes.lastOrNull()?.sql.orEmpty())).apply { foreground = UIUtil.getContextHelpForeground() })

    val results = outcomes.filterIsInstance<DbOutcome.Rows>()
    holders.clear()
    aggregates.text = ""
    val center: JComponent? = when {
      outcomes.size == 1 && results.size == 1 -> grid(results[0], disposable)
      outcomes.size == 1 && failed != null -> textArea(failed.message)
      outcomes.size == 1 -> null
      else -> JBTabbedPane().apply {
        results.forEachIndexed { i, r -> addTab(PhantomDbBundle.message("db.run.result.tab", i + 1), grid(r, disposable)) }
        addTab(PhantomDbBundle.message("db.run.output.tab"), textArea(outcomes.joinToString("\n") { line(it) }))
        selectedIndex = if (failed != null || results.isEmpty()) tabCount - 1 else results.size - 1
        addChangeListener { aggregates.text = "" }
      }
    }
    // a select from one table (not a table's data view, which has its own fields) can be filtered and sorted again;
    // the fields stay while it is run filtered, also when a filter fails
    val single = results.singleOrNull()?.takeIf { outcomes.size == 1 }
    if (filterBar == null && single != null && table == null && dataSource != null) {
      SingleTableSelect.parse(single.sql)?.let { name -> startFilter(single, name) }
    }
    shownRows = results.lastOrNull()?.rows?.map { it.toList() }
    val north = JPanel(BorderLayout())
    north.add(top, BorderLayout.CENTER)
    if (holders.isNotEmpty()) north.add(tools(), BorderLayout.EAST)
    this.center = center
    replace(north, center, status, disposable)
  }

  @get:TestOnly
  var shownPlan: DbPlan? = null
    private set

  /** An EXPLAIN result as a plan tree; anything that is not one plan (a failure, no rows) shows as usual. */
  fun showPlan(outcomes: List<DbOutcome>) {
    val rows = outcomes.singleOrNull() as? DbOutcome.Rows
    val plan = rows?.let { DbPlan.parse(it) }
    if (rows == null || plan == null) return showOutcomes(outcomes)
    val top = row()
    val status = PhantomDbBundle.message("db.plan.status", plan.root.all().count(), rows.elapsedMs)
    top.add(JBLabel(status, AllIcons.General.InspectionsOK, JBLabel.LEFT))
    top.add(JBLabel(SqlSafety.preview(rows.sql)).apply { foreground = UIUtil.getContextHelpForeground() })
    dropFilter()
    holders.clear()
    replace(top, DbPlanView(plan), status)
    shownPlan = plan
  }

  /** The selection of [grid] in [format]; null when an INSERT target was asked for and not given. */
  internal fun copyText(grid: DataGrid, format: DbExportFormat): String? {
    val holder = holders.firstOrNull { it.grid === grid } ?: return null
    val dbms = dataSource?.dialect() ?: dev.phantom.db.core.Dbms.MYSQL
    val rows = DbGridSelection.rows(grid, holder.rows.columns, holder.rows.sql)
    val target = if (format.needsTable) insertTarget(holder.rows, dbms) ?: DbExportUi.askTable(project) ?: return null else null
    return format.render(rows, target, dbms, updateKeys(DbGridSelection.columns(grid)))
  }

  /** What a Data Extractor needs to write [grid]'s rows; null for a grid this panel does not show. */
  internal fun extractionContext(grid: DataGrid): DbExtractionContext? {
    val holder = holders.firstOrNull { it.grid === grid } ?: return null
    val dbms = dataSource?.dialect() ?: dev.phantom.db.core.Dbms.MYSQL
    return DbExtractionContext(holder.rows.columns, insertTarget(holder.rows, dbms), dbms, shownEditing?.target?.keyColumns.orEmpty())
  }

  /** The key of the edited table among [columns] (result column indices), empty unless all of it is there. */
  internal fun updateKeys(columns: List<Int>): List<Int> {
    val keys = shownEditing?.target?.keyColumns.orEmpty()
    return if (keys.isNotEmpty() && columns.containsAll(keys)) keys.map(columns::indexOf) else emptyList()
  }

  /** Where INSERTs of [rows] go: the edited table, the data view's table or the table of a single-table select. */
  internal fun insertTarget(rows: DbOutcome.Rows, dbms: dev.phantom.db.core.Dbms): String? =
    shownEditing?.target?.tableSql ?: table?.let { DbNames.table(dbms, it) } ?: DbExportFormat.tableSql(dbms, SingleTableSelect.parse(rows.sql))

  private fun startFilter(rows: DbOutcome.Rows, name: DbTableName) {
    val ds = dataSource ?: return
    filterBase = rows.sql
    val columns = rows.columns.map { DbColumnInfo(it.name, it.typeName.orEmpty(), true, false) }
    filterBar = DbFilterBar(project, "${ds.id}|query|${name.schema}|${name.table}", DbFilterContext { columns }) { refilter() }
  }

  private fun dropFilter() {
    filterBar = null
    filterBase = null
  }

  /** Runs the select again with the WHERE / ORDER BY fields added. */
  fun refilter() {
    val base = filterBase ?: return
    val bar = filterBar ?: return
    val ds = dataSource ?: return
    DbTableQuery.problem(bar.where, bar.orderBy)?.let { return leaveEdits { showError(it) } }
    val sql = SingleTableSelect.refilter(base, bar.where, bar.orderBy) ?: return
    when (val verdict = SqlSafety.check(ds, listOf(sql))) {
      is SqlSafety.Verdict.Blocked -> return leaveEdits { showError(verdict.message) }
      is SqlSafety.Verdict.Confirm -> if (com.intellij.openapi.ui.Messages.showOkCancelDialog(project, verdict.message, PhantomDbBundle.message("db.safety.title"),
                                                                                           PhantomDbBundle.message("db.safety.run"),
                                                                                           com.intellij.openapi.ui.Messages.getCancelButton(),
                                                                                           com.intellij.openapi.ui.Messages.getWarningIcon()) != com.intellij.openapi.ui.Messages.OK) return
      SqlSafety.Verdict.Ok -> {}
    }
    val runIn = schema
    leaveEdits {
      showRunning(SqlSafety.preview(sql), keepFilter = true)
      job = DbConnectionService.getInstance(project).launch {
        try {
          val outcomes = DbExecutor.execute(project, ds, listOf(sql), schema = runIn)
          withContext(Dispatchers.EDT) { showOutcomes(outcomes) }
        }
        catch (e: java.sql.SQLException) {
          withContext(Dispatchers.EDT) { showError(DbExecutor.message(e)) }
        }
        catch (e: DbException) {
          withContext(Dispatchers.EDT) { showError(e.message.orEmpty()) }
        }
      }
    }
  }

  /**
   * Runs [then], which replaces the shown result or closes its tab, once the grid's waiting changes are dealt with: at
   * once without any, else after the user chose Submit (then only if every change was written, on the EDT), Discard, or
   * Cancel (never).
   */
  fun leaveEdits(then: () -> Unit) {
    val edits = shownEditing
    if (edits == null || !edits.hasPendingChanges() || project.isDisposed || !project.isOpen) return then()
    when (DbUnsubmittedChanges.ask(this)) {
      DbUnsubmittedAnswer.CANCEL -> {}
      DbUnsubmittedAnswer.DISCARD -> then()
      DbUnsubmittedAnswer.SUBMIT -> {
        val source = com.intellij.database.datagrid.GridRequestSource(shownGrid?.let { com.intellij.database.run.ui.DataGridRequestPlace(it) })
        edits.submit(source) { ok -> if (ok) withContext(Dispatchers.EDT) { then() } }
      }
    }
  }

  private fun summary(outcome: DbOutcome): String = when (outcome) {
    is DbOutcome.Rows -> PhantomDbBundle.message("db.run.rows", outcome.rows.size, if (outcome.truncated) "+" else "", outcome.elapsedMs)
    is DbOutcome.Updated -> PhantomDbBundle.message("db.run.updated", outcome.count, outcome.elapsedMs) +
                            (if (pendingIn(outcome)) " " + PhantomDbBundle.message("db.run.not.committed") else "")
    is DbOutcome.Failed -> firstLine(outcome.message)
  }

  /** Whether [outcome]'s statement is waiting in an open transaction (manual-commit data source). */
  private fun pendingIn(outcome: DbOutcome): Boolean =
    DbPendingChanges.getInstanceIfCreated(project)?.let { p -> p.dataSourcesWithChanges().any { ds -> p.of(ds.id).any { it.sql == outcome.sql } } } == true

  private fun line(outcome: DbOutcome): String {
    val mark = if (outcome is DbOutcome.Failed) "✗" else "✓"
    return "$mark ${SqlSafety.preview(outcome.sql)} — ${if (outcome is DbOutcome.Failed) outcome.message else summary(outcome)}"
  }

  private fun grid(rows: DbOutcome.Rows, parent: Disposable): JComponent {
    val model = DataGridListModel { a, b -> a == b }
    val hookUp = DbEditableHookUp(project, model)
    val tableActions = if (table == null) emptyList() else listOf(DbForeignKeyActions.group(), Separator.getInstance())
    val manager = com.intellij.openapi.actionSystem.ActionManager.getInstance()
    val setValue = listOf("PhantomDb.Grid.Submit", "PhantomDb.Grid.SubmitAndCommit", "Console.TableResult.RevertSelected", "PhantomDb.Grid.PreviewChanges", "PhantomDb.Grid.SubmitImmediately")
                     .mapNotNull { manager.getAction(it) } + Separator.getInstance() +
                   listOf(manager.getAction("PhantomDb.Grid.SetNull"), manager.getAction("PhantomDb.Grid.SetDefault"), Separator.getInstance()) +
                   listOf("PhantomDb.Grid.EditFilterCriteria", "PhantomDb.Grid.EditOrdering", "PhantomDb.Grid.SelectRow", "PhantomDb.Grid.SelectColumn", "PhantomDb.Grid.ColumnStatistics")
                     .mapNotNull { manager.getAction(it) } + Separator.getInstance()
    val popup = DefaultActionGroup(tableActions + setValue + listOf(DbFilterByValue.group(), DbCopyActions.group(), Separator.getInstance(), GridUtil.getGridPopupActions()))
    val grid = GridUtil.createDataGrid(project, hookUp, popup,
                                       BiConsumer { g, appearance ->
                                         // the setup the community CSV editor uses: helpers, renderers, formatters, cell editors
                                         GridUtil.configureCsvTable(g, appearance)
                                         GridUtil.configureFullSizeTable(g, appearance)
                                         DbObjectFormatter.install(g)
                                       DbUnsetCells.install(g)
                                       })
    Disposer.register(parent, grid)
    if (table != null) DbForeignKeyActions.installShortcut(grid)
    // fill through the updater so the grid gets the column and row events it lays itself out from
    val updater = hookUp.createUpdater()
    updater.setColumns(rows.columns.mapIndexed { i, c -> DataConsumer.Column(i, c.name, c.sqlType, c.typeName, c.className, c.precision, c.scale, null, null, null) })
    updater.addRows(rows.rows.mapIndexed { i, values -> DataConsumer.Row.create(i, DbGridTime.toGrid(values)) })
    updater.afterLastRowAdded()
    if (transposed) grid.resultView.isTransposed = true
    val holder = GridHolder(grid, hookUp, rows)
    // summing a selection and decoding a cell wait until the selection rests, so dragging over cells stays smooth
    val settle = Alarm(Alarm.ThreadToUse.SWING_THREAD, grid)
    grid.resultView.addSelectionChangedListener {
      if (Disposer.isDisposed(grid)) return@addSelectionChangedListener
      settle.cancelAllRequests()
      settle.addRequest({
        val values = DbGridSelection.values(grid)
        aggregates.text = if (values.size > 1) DbAggregates.of(values).text() else ""
        holder.viewer?.update()
      }, SETTLE_MS)
    }
    holders.add(holder)
    if (valueViewer) holder.showViewer(true)
    onGrid?.invoke(grid)
    shownEditing = null
    val source = dataSource
    if (source != null) {
      // finding the table and its primary key needs the database; until then the grid is read-only
      val resolving = DbConnectionService.getInstance(project).launch {
        val target = DbEditTargets.resolve(project, source, rows, schema) ?: return@launch
        withContext(Dispatchers.EDT) {
          if (Disposer.isDisposed(grid)) return@withContext
          val edits = DbGridEdits(project, source, target, hookUp).also { shownEditing = it }
          hookUp.editing = edits
          // the grid made its change colors with the mutator it had then, none, so changed, new and deleted cells looked as
          // the others; that layer passes colors through, and removeLayer fails on its fixed-size list, so it stays
          (grid.colorModel as? GridColorModelImpl)?.addLayer(MutationsColorLayer(edits))
          holder.viewer?.update()
        }
      }
      Disposer.register(parent) { resolving.cancel() }
    }
    return holder
  }

  /** A grid, and the value viewer at its right when that is on. */
  private inner class GridHolder(val grid: DataGrid, val hookUp: DbEditableHookUp, val rows: DbOutcome.Rows) : JPanel(BorderLayout()) {
    var viewer: DbValueViewer? = null
      private set

    init {
      add(grid.panel.component, BorderLayout.CENTER)
    }

    fun showViewer(on: Boolean) {
      if (on == (viewer != null)) return
      removeAll()
      if (on) {
        val v = DbValueViewer(project, grid, hookUp).also { viewer = it }
        Disposer.register(grid, v)
        add(OnePixelSplitter(false, 0.65f).apply {
          firstComponent = grid.panel.component
          secondComponent = v
        }, BorderLayout.CENTER)
      }
      else {
        viewer?.let { Disposer.dispose(it) }
        viewer = null
        add(grid.panel.component, BorderLayout.CENTER)
      }
      revalidate()
      repaint()
    }
  }

  private fun currentHolder(): GridHolder? = when (val c = center) {
    is GridHolder -> c
    is JBTabbedPane -> c.selectedComponent as? GridHolder
    else -> null
  }

  private fun tools(): JComponent {
    val group = DefaultActionGroup(
      // Submit and Revert for the shown grid: its waiting edits, as on DataGrip's result toolbar
      object : DumbAwareAction(PhantomDbBundle.message("action.PhantomDb.Grid.Submit.text"), null, AllIcons.Actions.Commit) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
          e.presentation.isVisible = shownEditing != null
          e.presentation.isEnabled = shownEditing?.hasPendingChanges() == true
        }

        override fun actionPerformed(e: AnActionEvent) {
          val grid = shownGrid ?: return
          shownEditing?.submit(com.intellij.database.datagrid.GridRequestSource(com.intellij.database.run.ui.DataGridRequestPlace(grid))) {}
        }
      },
      object : DumbAwareAction(PhantomDbBundle.message("db.grid.revert"), null, AllIcons.Actions.Rollback) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
          e.presentation.isVisible = shownEditing != null
          e.presentation.isEnabled = shownEditing?.hasPendingChanges() == true
        }

        override fun actionPerformed(e: AnActionEvent) {
          val grid = shownGrid ?: return
          val edits = shownEditing ?: return
          edits.revert(com.intellij.database.datagrid.GridRequestSource(com.intellij.database.run.ui.DataGridRequestPlace(grid)), edits.affectedRows,
                       com.intellij.database.datagrid.ModelIndexSet.forColumns(edits.hookUp.dataModel, *IntArray(edits.hookUp.dataModel.columnCount) { it }))
        }
      },
      Separator.getInstance(),
      object : DumbAwareToggleAction(PhantomDbBundle.message("db.grid.transpose"), null, AllIcons.Actions.SplitVertically) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent): Boolean = transposed
        override fun setSelected(e: AnActionEvent, state: Boolean) {
          transposed = state
        }
      },
      object : DumbAwareToggleAction(PhantomDbBundle.message("db.grid.value.viewer"), null, AllIcons.Actions.PreviewDetails) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent): Boolean = valueViewer
        override fun setSelected(e: AnActionEvent, state: Boolean) {
          valueViewer = state
        }
      },
      object : DefaultActionGroup(PhantomDbBundle.message("db.copy.as"), true) {
        init {
          templatePresentation.icon = AllIcons.Actions.Copy
          DbExportFormat.entries.forEach { format ->
            add(object : DumbAwareAction(format.title) {
              override fun actionPerformed(e: AnActionEvent) {
                val grid = shownGrid ?: return
                copyText(grid, format)?.let { CopyPasteManager.getInstance().setContents(StringSelection(it)) }
              }
            })
          }
        }
      },
    )
    val toolbar = ActionManager.getInstance().createActionToolbar("PhantomDbResult", group, true)
    toolbar.targetComponent = this
    return JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
      add(aggregates)
      add(toolbar.component)
    }
  }

  private fun textArea(text: String): JComponent = JBScrollPane(JBTextArea(text).apply {
    isEditable = false
    font = JBUI.Fonts.create(java.awt.Font.MONOSPACED, font.size)
    border = JBUI.Borders.empty(4, 8)
  }).apply { border = JBUI.Borders.empty() }

  private fun firstLine(s: String): String = s.lineSequence().firstOrNull().orEmpty().let { if (it.length > 160) it.take(157) + "…" else it }

  private fun row(): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(4)))

  private fun replace(north: JComponent, center: JComponent?, status: String, disposable: Disposable? = null) {
    shown?.let { Disposer.dispose(it) }
    shown = disposable
    shownEditing = null
    disposable?.let { Disposer.register(this, it) }
    if (disposable == null) {
      shownRows = null
      holders.clear()
      this.center = center
    }
    shownPlan = null
    shownStatus = status
    removeAll()
    val bar = filterBar
    add(if (bar == null) north else JPanel(BorderLayout()).apply {
      add(bar, BorderLayout.NORTH)
      add(north, BorderLayout.CENTER)
    }, BorderLayout.NORTH)
    if (center != null) add(center, BorderLayout.CENTER)
    revalidate()
    repaint()
  }

  override fun dispose() {
    job?.cancel()
  }

  private companion object {
    const val SETTLE_MS = 150
  }
}

/**
 * The results tool window, registered on the first run so it costs nothing before. A run replaces the unpinned tab of
 * its console (or `.sql` file) and is titled with the first line of its SQL; pinned tabs are kept and a new one opens
 * next to them. Close Other Tabs and Close All Tabs leave pinned tabs alone.
 */
object DbResultsToolWindow {
  const val ID: String = "Phantom DB Results"
  private const val MAX_TABS = 10

  /**
   * Hands [then] the tab for [file] once its shown result may be replaced: at once for a new or clean tab, else after
   * [DbResultPanel.leaveEdits]. The tab is only retitled and selected then, so Cancel leaves it as it was.
   */
  fun tabForGuarded(project: Project, file: VirtualFile, dataSource: DbDataSource, sql: String? = null, then: (DbResultPanel) -> Unit) {
    val key = "${file.url}|${dataSource.id}"
    val existing = toolWindow(project).contentManager.contents.firstOrNull { !it.isPinned && (it.component as? DbResultPanel)?.tabKey == key }
      ?.component as? DbResultPanel
    if (existing == null) then(tabFor(project, file, dataSource, sql))
    else existing.leaveEdits { then(tabFor(project, file, dataSource, sql)) }
  }

  /** [sql]: what is about to run, for the tab title; without it the tab is named after the console or file. */
  fun tabFor(project: Project, file: VirtualFile, dataSource: DbDataSource, sql: String? = null): DbResultPanel {
    val toolWindow = toolWindow(project)
    val manager = toolWindow.contentManager
    val key = "${file.url}|${dataSource.id}"
    val place = if (DbConsoles.isConsole(file)) dataSource.name else "${file.name} · ${dataSource.name}"
    val existing = manager.contents.firstOrNull { !it.isPinned && (it.component as? DbResultPanel)?.tabKey == key }
    val content = existing ?: ContentFactory.getInstance().createContent(DbResultPanel(project).also { it.tabKey = key }, place, false).also { content ->
      content.setDisposer(content.component as DbResultPanel)
      content.isPinnable = true
      manager.addContent(content)
      trim(manager)
    }
    val panel = content.component as DbResultPanel
    panel.dataSource = dataSource
    content.displayName = sql?.let { title(it) } ?: place
    content.description = place
    paint(project, content)
    manager.setSelectedContent(content)
    toolWindow.show()
    return panel
  }

  /** The first line of [sql] with text, shortened. */
  fun title(sql: String): String {
    val line = SingleTableSelect.stripComments(sql).lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: sql.trim()
    return if (line.length > 40) line.take(39) + "…" else line
  }

  /** The tabs of [file] with a statement still running. */
  fun running(project: Project, file: VirtualFile): List<DbResultPanel> =
    ToolWindowManager.getInstance(project).getToolWindow(ID)?.contentManager?.contents.orEmpty()
      .mapNotNull { it.component as? DbResultPanel }
      .filter { it.tabKey?.startsWith(file.url + "|") == true && it.job?.isActive == true }

  /** Closes every tab but [keep] and the pinned ones. */
  fun closeOthers(project: Project, keep: Content?) {
    val manager = ToolWindowManager.getInstance(project).getToolWindow(ID)?.contentManager ?: return
    manager.contents.filter { it !== keep && !it.isPinned }.forEach { manager.removeContent(it, true) }
  }

  /** Closes every tab that is not pinned. */
  fun closeAll(project: Project) = closeOthers(project, null)

  private fun trim(manager: com.intellij.ui.content.ContentManager) {
    val unpinned = manager.contents.filter { !it.isPinned }
    if (unpinned.size > MAX_TABS) unpinned.take(unpinned.size - MAX_TABS).forEach { manager.removeContent(it, true) }
  }

  /** A tab for a table's data view, one per table, reused when the table is opened again. */
  fun tableTab(project: Project, title: String, existing: (JComponent) -> Boolean, create: () -> JComponent): JComponent {
    val toolWindow = toolWindow(project)
    val manager = toolWindow.contentManager
    val content = manager.contents.firstOrNull { existing(it.component) } ?: ContentFactory.getInstance().createContent(create(), title, false).also { content ->
      (content.component as? Disposable)?.let { content.setDisposer(it) }
      content.isPinnable = true
      manager.addContent(content)
      trim(manager)
    }
    paint(project, content)
    manager.setSelectedContent(content)
    toolWindow.show()
    return content.component
  }

  /** Gives every result tab the stripe of its data source's color, e.g. after a data source was saved. */
  fun repaintTabs(project: Project) {
    ToolWindowManager.getInstance(project).getToolWindow(ID)?.contentManager?.contents?.forEach { paint(project, it) }
  }

  /** A top stripe in the data source's color: tool window tabs do not paint a content's tab color. */
  private fun paint(project: Project, content: Content) {
    DbColorService.getInstance(project)
    val component = content.component
    val id = when (component) {
      is DbResultPanel -> component.dataSource?.id
      is DbTableDataView -> component.dataSourceId
      else -> return
    }
    val stripe = DbColors.stripe(DbDataSourceService.getInstance(project).find(id)?.color)
    component.border = if (stripe != null) javax.swing.BorderFactory.createMatteBorder(JBUI.scale(3), 0, 0, 0, stripe) else JBUI.Borders.empty()
  }

  private fun toolWindow(project: Project): ToolWindow {
    val manager = ToolWindowManager.getInstance(project)
    return manager.getToolWindow(ID) ?: manager.registerToolWindow(ID) {
      icon = AllIcons.Nodes.DataTables
      anchor = ToolWindowAnchor.BOTTOM
      stripeTitle = Supplier { PhantomDbBundle.message("db.results.title") }
      canCloseContent = true
      hideOnEmptyContent = false
    }.also {
      it.setAdditionalGearActions(tabActions(project))
      it.contentManager.addContentManagerListener(closeGuard(project, it.contentManager))
    }
  }

  /** Asks about unsubmitted grid changes before a tab closes: the close is held back, then repeated once they are dealt with. */
  private fun closeGuard(project: Project, manager: com.intellij.ui.content.ContentManager) = object : ContentManagerListener {
    private val closing = mutableSetOf<Content>()

    override fun contentRemoveQuery(event: ContentManagerEvent) {
      val content = event.content
      if (content in closing || project.isDisposed || !project.isOpen) return
      val panel = when (val c = content.component) {
        is DbResultPanel -> c
        is DbTableDataView -> c.result
        else -> return
      }
      if (panel.shownEditing?.hasPendingChanges() != true) return
      event.consume()
      panel.leaveEdits {
        closing.add(content)
        try {
          manager.removeContent(content, true)
        }
        finally {
          closing.remove(content)
        }
      }
    }
  }

  private fun tabActions(project: Project) = DefaultActionGroup(
    object : DumbAwareToggleAction(PhantomDbBundle.message("db.results.pin")) {
      override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
      override fun isSelected(e: AnActionEvent): Boolean = selected(project)?.isPinned == true
      override fun setSelected(e: AnActionEvent, state: Boolean) {
        selected(project)?.isPinned = state
      }
    },
    object : DumbAwareAction(PhantomDbBundle.message("db.results.close.others")) {
      override fun actionPerformed(e: AnActionEvent) = closeOthers(project, selected(project))
    },
    object : DumbAwareAction(PhantomDbBundle.message("db.results.close.all")) {
      override fun actionPerformed(e: AnActionEvent) = closeAll(project)
    },
  )

  private fun selected(project: Project): Content? = ToolWindowManager.getInstance(project).getToolWindow(ID)?.contentManager?.selectedContent
}

/** Stops what the editor's file is running, on Stop's keys (Cmd+F2 / Ctrl+F2) as DataGrip's Cancel. */
class DbCancelAction : com.intellij.openapi.project.DumbAwareAction() {
  override fun getActionUpdateThread(): com.intellij.openapi.actionSystem.ActionUpdateThread = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT

  override fun update(e: com.intellij.openapi.actionSystem.AnActionEvent) {
    val project = e.project
    val file = e.getData(com.intellij.openapi.actionSystem.CommonDataKeys.VIRTUAL_FILE)
    e.presentation.isEnabledAndVisible = project != null && file != null && DbResultsToolWindow.running(project, file).isNotEmpty()
  }

  override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
    val project = e.project ?: return
    val file = e.getData(com.intellij.openapi.actionSystem.CommonDataKeys.VIRTUAL_FILE) ?: return
    DbResultsToolWindow.running(project, file).forEach { it.job?.cancel() }
  }
}

/** What the user chose for grid changes not yet submitted. */
enum class DbUnsubmittedAnswer { SUBMIT, DISCARD, CANCEL }

/** The question behind [DbResultPanel.leaveEdits]. */
object DbUnsubmittedChanges {
  /** Answers for [panel]'s waiting changes; tests replace it (and put it back), as the dialog cannot show there. */
  internal var ask: (DbResultPanel) -> DbUnsubmittedAnswer = { panel ->
    if (ApplicationManager.getApplication().isUnitTestMode) DbUnsubmittedAnswer.DISCARD
    else when (Messages.showYesNoCancelDialog(panel.project, PhantomDbBundle.message("db.unsubmitted.message"), PhantomDbBundle.message("db.unsubmitted.title"),
                                              PhantomDbBundle.message("db.unsubmitted.submit"), PhantomDbBundle.message("db.unsubmitted.discard"),
                                              Messages.getCancelButton(), Messages.getWarningIcon())) {
      Messages.YES -> DbUnsubmittedAnswer.SUBMIT
      Messages.NO -> DbUnsubmittedAnswer.DISCARD
      else -> DbUnsubmittedAnswer.CANCEL
    }
  }
}
