// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import com.intellij.icons.AllIcons
import dev.phantom.db.core.dialect
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.*
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.Text
import com.intellij.util.xmlb.annotations.XCollection
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.DbDataSourceService
import dev.phantom.db.core.PhantomDbBundle
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.JList

/** One statement that ran from a console: what, where, when, and how it ended. */
@Tag("query")
class DbHistoryEntry {
  @Text var sql: String = ""
  var dataSourceId: String = ""
  var time: Long = 0
  var elapsedMs: Long = 0
  var summary: String = ""
  var failed: Boolean = false
}

/**
 * The last [LIMIT] statements run from SQL consoles and files, newest first, kept in the workspace file. Grid edits
 * are not recorded: they are in Database Changes.
 */
@Service(Service.Level.PROJECT)
@State(name = "PhantomDbQueryHistory", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class DbQueryHistory : PersistentStateComponent<DbQueryHistory.State> {
  class State {
    @XCollection(style = XCollection.Style.v2)
    var entries: MutableList<DbHistoryEntry> = ArrayList()
  }

  private var state = State()

  override fun getState(): State = state
  override fun loadState(state: State) { this.state = state }

  val entries: List<DbHistoryEntry> get() = synchronized(this) { state.entries.toList() }

  fun record(dataSource: DbDataSource, outcome: DbOutcome) {
    val entry = DbHistoryEntry().apply {
      sql = outcome.sql.trim()
      dataSourceId = dataSource.id
      time = System.currentTimeMillis()
      elapsedMs = outcome.elapsedMs
      failed = outcome is DbOutcome.Failed
      summary = when (outcome) {
        is DbOutcome.Rows -> "${outcome.rows.size}${if (outcome.truncated) "+" else ""} rows"
        is DbOutcome.Updated -> "${outcome.count} updated"
        is DbOutcome.Failed -> outcome.message.lineSequence().firstOrNull().orEmpty().take(120)
      }
    }
    if (entry.sql.isEmpty()) return
    synchronized(this) {
      // running the same statement again moves it to the top instead of repeating it
      state.entries.removeIf { it.sql == entry.sql && it.dataSourceId == entry.dataSourceId }
      state.entries.add(0, entry)
      while (state.entries.size > LIMIT) state.entries.removeAt(state.entries.size - 1)
    }
  }

  fun clear() = synchronized(this) { state.entries.clear() }

  companion object {
    const val LIMIT: Int = 500

    fun getInstance(project: Project): DbQueryHistory = project.service()
  }
}

/** A searchable list of past statements; choosing one appends it to its data source's console. */
class DbShowHistoryAction : DumbAwareAction() {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    // in editors only for SQL; always in the Database tool window
    val file = e.getData(com.intellij.openapi.actionSystem.CommonDataKeys.PSI_FILE)
    e.presentation.isEnabledAndVisible = e.project != null && (file == null || file.language == dev.phantom.db.core.SqlLanguage)
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val history = DbQueryHistory.getInstance(project).entries
    val sources = DbDataSourceService.getInstance(project).dataSources.associateBy { it.id }
    val format = SimpleDateFormat("MM-dd HH:mm")
    val builder = JBPopupFactory.getInstance().createPopupChooserBuilder(history)
      .setTitle(PhantomDbBundle.message("db.history.title", history.size))
      .setRenderer(object : ColoredListCellRenderer<DbHistoryEntry>() {
        override fun customizeCellRenderer(list: JList<out DbHistoryEntry>, value: DbHistoryEntry, index: Int, selected: Boolean, hasFocus: Boolean) {
          icon = if (value.failed) AllIcons.General.Error else sources[value.dataSourceId]?.dialect()?.icon ?: AllIcons.Nodes.DataTables
          append(SqlSafety.preview(value.sql).take(140))
          append("   " + listOfNotNull(sources[value.dataSourceId]?.name, value.summary, "${value.elapsedMs} ms", format.format(Date(value.time)))
            .joinToString(" · "), SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
      })
      .setNamerForFiltering { it.sql }
      .setItemChosenCallback { entry ->
        val source = sources[entry.dataSourceId] ?: return@setItemChosenCallback
        DbConsoles.openConsole(project, source, entry.sql.trimEnd().let { if (it.endsWith(";")) it else "$it;" })
      }
    if (history.isEmpty()) builder.setAdText(PhantomDbBundle.message("db.history.empty"))
    builder.createPopup().showCenteredInCurrentWindow(project)
  }
}
