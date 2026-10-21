// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import com.intellij.database.datagrid.*
import dev.phantom.db.core.dialect
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.phantom.db.core.DbColorService
import dev.phantom.db.core.DbColors
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.DbDataSourceService
import dev.phantom.db.core.PhantomDbBundle
import dev.phantom.db.core.ui.DbForeignKeyActions
import dev.phantom.db.core.ui.DbTableDataView
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
 * One tab of the results tool window: a status line ("12 rows · 8 ms"), then the rows in the community data grid
 * (sorting, copying, value viewer). Several statements at once get one tab per result plus an Output tab.
 */
class DbResultPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
  var job: Job? = null

  /** The data source the shown results came from; a single-table result from it can be edited in place. */
  var dataSource: DbDataSource? = null

  /** The schema the shown results ran in; an edited unqualified table is looked up there. */
  var schema: DbSchemaChoice? = null

  /** The table a data view shows; its grid then offers the foreign key actions. */
  var table: DbTableRef? = null

  /** Called with each new grid, so a data view can tell which grid is current. */
  var onGrid: ((DataGrid) -> Unit)? = null

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

  init {
    border = JBUI.Borders.empty()
  }

  fun showRunning(preview: String) {
    job?.cancel()
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
        DbExportUi.show(project, link, shownResult, dataSource?.dialect() ?: dev.phantom.db.core.Dbms.MYSQL)
      }
      top.add(link)
    }
    top.add(JBLabel(SqlSafety.preview(outcomes.lastOrNull()?.sql.orEmpty())).apply { foreground = UIUtil.getContextHelpForeground() })

    val results = outcomes.filterIsInstance<DbOutcome.Rows>()
    val center: JComponent? = when {
      outcomes.size == 1 && results.size == 1 -> grid(results[0], disposable)
      outcomes.size == 1 && failed != null -> textArea(failed.message)
      outcomes.size == 1 -> null
      else -> JBTabbedPane().apply {
        results.forEachIndexed { i, r -> addTab(PhantomDbBundle.message("db.run.result.tab", i + 1), grid(r, disposable)) }
        addTab(PhantomDbBundle.message("db.run.output.tab"), textArea(outcomes.joinToString("\n") { line(it) }))
        selectedIndex = if (failed != null || results.isEmpty()) tabCount - 1 else results.size - 1
      }
    }
    shownRows = results.lastOrNull()?.rows?.map { it.toList() }
    replace(top, center, status, disposable)
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
    val popup = if (table == null) GridUtil.getGridPopupActions()
                else com.intellij.openapi.actionSystem.DefaultActionGroup(DbForeignKeyActions.group(), com.intellij.openapi.actionSystem.Separator.getInstance(),
                                                                          GridUtil.getGridPopupActions())
    val grid = GridUtil.createDataGrid(project, hookUp, popup,
                                       BiConsumer { g, appearance ->
                                         // the setup the community CSV editor uses: helpers, renderers, formatters, cell editors
                                         GridUtil.configureCsvTable(g, appearance)
                                         GridUtil.configureFullSizeTable(g, appearance)
                                       })
    Disposer.register(parent, grid)
    if (table != null) DbForeignKeyActions.installShortcut(grid)
    onGrid?.invoke(grid)
    // fill through the updater so the grid gets the column and row events it lays itself out from
    val updater = hookUp.createUpdater()
    updater.setColumns(rows.columns.mapIndexed { i, c -> DataConsumer.Column(i, c.name, c.sqlType, c.typeName, c.className) })
    updater.addRows(rows.rows.mapIndexed { i, values -> DataConsumer.Row.create(i, values) })
    updater.afterLastRowAdded()
    shownEditing = null
    val source = dataSource
    if (source != null) {
      // finding the table and its primary key needs the database; until then the grid is read-only
      val resolving = DbConnectionService.getInstance(project).launch {
        val target = DbEditTargets.resolve(project, source, rows, schema) ?: return@launch
        withContext(Dispatchers.EDT) {
          if (Disposer.isDisposed(grid)) return@withContext
          hookUp.editing = DbGridEdits(project, source, target, hookUp).also { shownEditing = it }
        }
      }
      Disposer.register(parent) { resolving.cancel() }
    }
    return grid.panel.component
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
    disposable?.let { Disposer.register(this, it) }
    if (disposable == null) shownRows = null
    shownStatus = status
    removeAll()
    add(north, BorderLayout.NORTH)
    if (center != null) add(center, BorderLayout.CENTER)
    revalidate()
    repaint()
  }

  override fun dispose() {
    job?.cancel()
  }
}

/**
 * The results tool window, registered on the first run so it costs nothing before. One tab per console (or `.sql`
 * file), reused by later runs; pinned tabs are kept and a new one opens next to them.
 */
object DbResultsToolWindow {
  const val ID: String = "Phantom DB Results"
  private const val MAX_TABS = 10

  fun tabFor(project: Project, file: VirtualFile, dataSource: DbDataSource): DbResultPanel {
    val toolWindow = toolWindow(project)
    val manager = toolWindow.contentManager
    val title = if (DbConsoles.isConsole(file)) dataSource.name else "${file.name} · ${dataSource.name}"
    val existing = manager.contents.firstOrNull { it.displayName == title && !it.isPinned }
    val panel = (existing?.component as? DbResultPanel ?: DbResultPanel(project).also { panel ->
      val content = ContentFactory.getInstance().createContent(panel, title, false)
      content.setDisposer(panel)
      manager.addContent(content)
      val unpinned = manager.contents.filter { !it.isPinned }
      if (unpinned.size > MAX_TABS) unpinned.take(unpinned.size - MAX_TABS).forEach { manager.removeContent(it, true) }
    }).also { it.dataSource = dataSource }
    manager.contents.firstOrNull { it.component === panel }?.let { paint(project, it); manager.setSelectedContent(it) }
    toolWindow.show()
    return panel
  }

  /** A tab for a table's data view, one per table, reused when the table is opened again. */
  fun tableTab(project: Project, title: String, existing: (JComponent) -> Boolean, create: () -> JComponent): JComponent {
    val toolWindow = toolWindow(project)
    val manager = toolWindow.contentManager
    val content = manager.contents.firstOrNull { existing(it.component) } ?: ContentFactory.getInstance().createContent(create(), title, false).also { content ->
      (content.component as? Disposable)?.let { content.setDisposer(it) }
      manager.addContent(content)
      val unpinned = manager.contents.filter { !it.isPinned }
      if (unpinned.size > MAX_TABS) unpinned.take(unpinned.size - MAX_TABS).forEach { manager.removeContent(it, true) }
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
    }
  }
}
