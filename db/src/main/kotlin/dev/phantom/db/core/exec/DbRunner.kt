// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.icons.AllIcons
import com.intellij.ide.scratch.ScratchFileService
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import dev.phantom.db.core.dialect
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.TokenType
import com.intellij.ui.EditorNotifications
import dev.phantom.db.core.*
import dev.phantom.db.core.PhantomDbIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.awt.Component
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Icon

/**
 * Consoles are plain `.sql` files in the IDE scratches ("Database Consoles" in Scratches and Consoles), one folder per
 * data source, so they survive restarts and the folder name (the data source id) tells which data source a console
 * belongs to. Consoles made before 0.13 stay in the IDE config directory and still count. A data source can have any
 * number of consoles; they share its console connection, so a manual-commit transaction is one per data source. Any
 * other `.sql` file can be attached to a data source too; that choice is kept per file in the workspace.
 */
object DbConsoles {
  private const val KEY_PREFIX = "phantom.db.dataSource:"
  private const val SCHEMA_PREFIX = "phantom.db.schema:"
  private const val CATALOG_PREFIX = "phantom.db.schemaCatalog:"

  /** Where consoles made before 0.13 live. */
  fun legacyRoot(): Path = PathManager.getConfigDir().resolve("phantom-db/consoles")

  /** Where new consoles go: the "Database Consoles" scratch root. */
  fun root(): Path = Path.of(ScratchFileService.getInstance().getRootPath(DbConsoleRootType.getInstance()))

  fun consoleDataSourceId(file: VirtualFile): String? {
    if (file.isDirectory) return null
    val path = runCatching { file.toNioPath() }.getOrNull() ?: return null
    val parent = path.parent ?: return null
    return if (parent.parent == root() || parent.parent == legacyRoot()) parent.fileName.toString() else null
  }

  fun isConsole(file: VirtualFile): Boolean = consoleDataSourceId(file) != null

  /** A console whose own data source is gone: it can be attached to another one. */
  fun isOrphan(project: Project, file: VirtualFile): Boolean =
    consoleDataSourceId(file)?.let { DbDataSourceService.getInstance(project).find(it) == null } == true

  fun dataSourceFor(project: Project, file: VirtualFile?): DbDataSource? {
    if (file == null) return null
    val service = DbDataSourceService.getInstance(project)
    consoleDataSourceId(file)?.let { id -> service.find(id)?.let { return it } }
    // any other file, or a console whose data source was removed and which was attached to another one
    return service.find(PropertiesComponent.getInstance(project).getValue(KEY_PREFIX + file.url))
  }

  /** Binds [file] to a data source; a schema chosen for another data source is dropped. */
  fun attach(project: Project, file: VirtualFile, dataSourceId: String?) {
    if (dataSourceFor(project, file)?.id != dataSourceId) {
      PropertiesComponent.getInstance(project).unsetValue(SCHEMA_PREFIX + file.url)
      PropertiesComponent.getInstance(project).unsetValue(CATALOG_PREFIX + file.url)
    }
    PropertiesComponent.getInstance(project).setValue(KEY_PREFIX + file.url, dataSourceId)
    EditorNotifications.getInstance(project).updateNotifications(file)
    DbColorService.getInstance(project).refresh(file)
  }

  /** The schema (catalog in MySQL) chosen for [file]; null for the one the connection opens in. Kept per file in the workspace. */
  fun schemaFor(project: Project, file: VirtualFile?): DbSchemaChoice? {
    val properties = PropertiesComponent.getInstance(project)
    val name = file?.let { properties.getValue(SCHEMA_PREFIX + it.url) }?.ifEmpty { null } ?: return null
    return DbSchemaChoice(name, properties.getValue(CATALOG_PREFIX + file.url)?.ifEmpty { null })
  }

  fun setSchema(project: Project, file: VirtualFile, schema: DbSchemaChoice?) {
    val chosen = schema?.takeUnless { it.isDefault }
    PropertiesComponent.getInstance(project).setValue(SCHEMA_PREFIX + file.url, chosen?.name)
    PropertiesComponent.getInstance(project).setValue(CATALOG_PREFIX + file.url, chosen?.catalog)
    EditorNotifications.getInstance(project).updateNotifications(file)
    // unqualified names now mean another schema's tables
    DaemonCodeAnalyzer.getInstance(project).restart("Phantom SQL schema chosen")
  }

  /** The data source's first console, created on first use. */
  fun consoleFile(dataSource: DbDataSource): VirtualFile? =
    consoles(dataSource.id).firstOrNull() ?: newConsoleFile(dataSource)

  /** The data source's consoles, older ones (config directory) first, then by name. Blocking file access. */
  fun consoles(dataSourceId: String): List<VirtualFile> =
    listOf(legacyRoot(), root()).flatMap { r -> sqlFiles(r.resolve(dataSourceId)) }
      .mapNotNull { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it) }

  /**
   * A new empty console of [dataSource]: `<name>.sql`, then `<name> [1].sql`, `<name> [2].sql`, … whichever is free.
   */
  fun newConsoleFile(dataSource: DbDataSource): VirtualFile? {
    val dir = root().resolve(dataSource.id).also { Files.createDirectories(it) }
    val taken = consoles(dataSource.id).map { it.name.lowercase() }.toSet()
    val base = FileUtil.sanitizeFileName(dataSource.name.ifBlank { dataSource.dialect().displayName }, false)
    val name = generateSequence(0) { it + 1 }.map { if (it == 0) "$base.sql" else "$base [$it].sql" }.first { it.lowercase() !in taken }
    return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir.resolve(name).also { Files.writeString(it, "") })
  }

  /**
   * Makes a new console file (on the calling thread, blocking file access), then on the EDT sets its schema ([schema]
   * null: where the connection opens; a console that once had this name may have left a choice behind) and opens it.
   */
  suspend fun newConsole(project: Project, dataSource: DbDataSource, schema: DbSchemaChoice?): VirtualFile? {
    val file = withContext(Dispatchers.IO) { newConsoleFile(dataSource) } ?: return null
    withContext(Dispatchers.EDT) {
      setSchema(project, file, schema)
      FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, file), true)
    }
    return file
  }

  private fun sqlFiles(dir: Path): List<Path> {
    if (!Files.isDirectory(dir)) return emptyList()
    return Files.list(dir).use { s -> s.filter { Files.isRegularFile(it) && it.toString().endsWith(".sql") }.toList() }
      .sortedWith(compareBy<Path> { it.fileName.toString().length }.thenBy { it.fileName.toString().lowercase() })
  }

  fun openConsole(project: Project, dataSource: DbDataSource, appendStatement: String? = null): VirtualFile? {
    val file = consoleFile(dataSource) ?: return null
    val editor = FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, file), true) ?: return file
    if (appendStatement != null) {
      val document = editor.document
      com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
        val text = document.charsSequence
        val prefix = if (text.isEmpty() || text.endsWith("\n")) "" else "\n"
        document.insertString(document.textLength, prefix + appendStatement + "\n")
      }
      editor.caretModel.moveToOffset(document.textLength - 1 - appendStatement.length)
      editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.MAKE_VISIBLE)
    }
    return file
  }
}

/** Token-level checks before running: no parsing, just the first keyword and whether `WHERE` appears. */
object SqlSafety {
  private val READ_ONLY_ALLOWED = setOf("SELECT", "SHOW", "EXPLAIN", "DESCRIBE", "DESC", "WITH", "VALUES", "TABLE", "USE", "SET")
  private val DDL = setOf("CREATE", "ALTER", "DROP", "RENAME", "TRUNCATE")
  private val TRANSACTION_CONTROL = setOf("COMMIT", "ROLLBACK", "BEGIN", "START", "SAVEPOINT", "RELEASE")

  sealed interface Verdict {
    data object Ok : Verdict
    data class Blocked(val message: String) : Verdict
    data class Confirm(val message: String) : Verdict
  }

  fun check(dataSource: DbDataSource, statements: List<String>): Verdict {
    if (dataSource.readOnly) {
      statements.firstOrNull { firstKeyword(it) !in READ_ONLY_ALLOWED }?.let {
        return Verdict.Blocked(PhantomDbBundle.message("db.safety.read.only", dataSource.name, preview(it)))
      }
    }
    statements.firstOrNull { missingWhere(it) }?.let {
      return Verdict.Confirm(PhantomDbBundle.message("db.safety.no.where", preview(it)))
    }
    return Verdict.Ok
  }

  fun isDdl(sql: String): Boolean = firstKeyword(sql) in DDL

  /** Whether the statement can change data or schema, i.e. is not a plain read. */
  fun changesData(sql: String): Boolean = firstKeyword(sql).let { it != null && it !in READ_ONLY_ALLOWED && it !in TRANSACTION_CONTROL }

  /** `COMMIT` or `ROLLBACK` typed in the console: it ends the open transaction itself. */
  fun endsTransaction(sql: String): Boolean = firstKeyword(sql) in setOf("COMMIT", "ROLLBACK")

  /** `UPDATE t SET …` or `DELETE FROM t` without a `WHERE` anywhere in the statement. */
  fun missingWhere(sql: String): Boolean {
    val words = keywords(sql)
    return words.firstOrNull() in setOf("UPDATE", "DELETE") && "WHERE" !in words
  }

  fun firstKeyword(sql: String): String? = keywords(sql).firstOrNull()

  private fun keywords(sql: String): List<String> {
    val lexer = SqlLexer()
    lexer.start(sql)
    val result = ArrayList<String>()
    while (lexer.tokenType != null) {
      val type = lexer.tokenType
      if (type == SqlTokens.KEYWORD) result.add(sql.substring(lexer.tokenStart, lexer.tokenEnd).uppercase())
      else if (type != TokenType.WHITE_SPACE && type !in SqlTokens.COMMENTS && type != SqlTokens.LPAREN && result.isEmpty()) result.add("")
      lexer.advance()
    }
    return result
  }

  fun preview(sql: String): String = sql.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ").let {
    if (it.length > 80) it.take(77) + "…" else it
  }
}

/** Runs SQL from an editor: picks the data source on the EDT, checks safety, executes on IO, shows results. */
object DbRunner {
  /**
   * Runs [statements] on the file's data source, asking for one when needed. [prepare] can rewrite them once the data
   * source is known (Explain needs its dialect), or return null to stop.
   */
  fun run(project: Project, file: VirtualFile, statements: List<String>, popupOwner: Component?,
          present: ((DbResultPanel, List<DbOutcome>) -> Unit)? = null, prepare: ((DbDataSource) -> List<String>?)? = null) {
    val sql = statements.map { it.trim() }.filter { it.isNotEmpty() }
    if (sql.isEmpty()) return
    fun go(dataSource: DbDataSource) {
      start(project, file, dataSource, if (prepare == null) sql else prepare(dataSource) ?: return, present)
    }
    val chosen = DbConsoles.dataSourceFor(project, file)
    val all = DbDataSourceService.getInstance(project).dataSources
    when {
      chosen != null -> go(chosen)
      all.size == 1 -> {
        DbConsoles.attach(project, file, all[0].id)
        go(all[0])
      }
      all.isEmpty() -> {
        val created = dev.phantom.db.core.ui.DbDataSourceDialog.create(project) ?: return
        DbConsoles.attach(project, file, created.id)
        go(created)
      }
      else -> chooseDataSource(project, file, popupOwner) { go(it) }
    }
  }

  fun chooseDataSource(project: Project, file: VirtualFile, owner: Component?, onChosen: (DbDataSource) -> Unit) {
    val all = DbDataSourceService.getInstance(project).dataSources
    val step = object : BaseListPopupStep<DbDataSource>(PhantomDbBundle.message("db.datasource.choose"), all) {
      override fun getTextFor(value: DbDataSource): String = value.name
      override fun getIconFor(value: DbDataSource): Icon = PhantomDbIcons.forDataSource(value)
      override fun onChosen(selectedValue: DbDataSource, finalChoice: Boolean): PopupStep<*>? {
        DbConsoles.attach(project, file, selectedValue.id)
        onChosen(selectedValue)
        return FINAL_CHOICE
      }
    }
    val popup = JBPopupFactory.getInstance().createListPopup(step)
    if (owner != null && owner.isShowing) popup.showInCenterOf(owner) else popup.showCenteredInCurrentWindow(project)
  }

  /**
   * Chooses the schema [file] runs in and resolves names against: "default" first, then the data source's schemas,
   * listed off the EDT.
   */
  fun chooseSchema(project: Project, file: VirtualFile, owner: Component?) {
    val dataSource = DbConsoles.dataSourceFor(project, file) ?: return
    DbConnectionService.getInstance(project).launch {
      val schemas = try {
        DbMetadataCache.getInstance(project).schemas(dataSource)
      }
      catch (e: CancellationException) {
        throw e
      }
      catch (e: Exception) {
        val message = (e as? java.sql.SQLException)?.let(DbExecutor::message) ?: e.message ?: e.javaClass.simpleName
        withContext(Dispatchers.EDT) {
          NotificationGroupManager.getInstance().getNotificationGroup("Phantom Database")
            .createNotification(PhantomDbBundle.message("db.schema.choose"), message, NotificationType.ERROR).notify(project)
        }
        return@launch
      }
      withContext(Dispatchers.EDT) {
        val current = DbConsoles.schemaFor(project, file) ?: DbSchemaChoice.DEFAULT
        val step = object : BaseListPopupStep<DbSchemaChoice>(PhantomDbBundle.message("db.schema.choose"), schemaChoices(schemas)) {
          override fun getTextFor(value: DbSchemaChoice): String = schemaText(value)
          override fun getIconFor(value: DbSchemaChoice): Icon = if (value.isDefault) AllIcons.Nodes.DataTables else AllIcons.Nodes.DataSchema
          override fun getDefaultOptionIndex(): Int = values.indexOf(current).coerceAtLeast(0)
          override fun onChosen(selectedValue: DbSchemaChoice, finalChoice: Boolean): PopupStep<*>? {
            DbConsoles.setSchema(project, file, selectedValue)
            return FINAL_CHOICE
          }
        }
        val popup = JBPopupFactory.getInstance().createListPopup(step)
        if (owner != null && owner.isShowing) popup.showInCenterOf(owner) else popup.showCenteredInCurrentWindow(project)
      }
    }
  }

  /** The default first, then the schemas; one listed in several catalogs keeps its catalog to tell them apart. */
  fun schemaChoices(schemas: List<DbSchemaRef>): List<DbSchemaChoice> {
    val named = schemas.filter { it.name.isNotEmpty() }
    val repeated = named.groupBy { it.name }.filterValues { refs -> refs.map { it.catalog }.distinct().size > 1 }.keys
    return listOf(DbSchemaChoice.DEFAULT) + named.map { DbSchemaChoice(it.name, if (it.name in repeated && it.schema != null) it.catalog else null) }.distinct()
  }

  fun schemaText(choice: DbSchemaChoice): String = when {
    choice.isDefault -> PhantomDbBundle.message("db.schema.default")
    choice.catalog != null -> "${choice.name} (${choice.catalog})"
    else -> choice.name
  }

  /** The schema statements from [file] run in: the default (where the connection opened) unless one was chosen. */
  fun executionSchema(project: Project, file: VirtualFile): DbSchemaChoice = DbConsoles.schemaFor(project, file) ?: DbSchemaChoice.DEFAULT

  /** [present]: shows the outcomes instead of the usual result grid (Explain shows a plan tree). */
  fun start(project: Project, file: VirtualFile, dataSource: DbDataSource, statements: List<String>,
            present: ((DbResultPanel, List<DbOutcome>) -> Unit)? = null) {
    val names = DbParameters.names(statements)
    val sql = if (names.isEmpty()) statements else DbParameters.substitute(statements, DbParameters.ask(project, statements, names) ?: return)
    when (val verdict = SqlSafety.check(dataSource, sql)) {
      is SqlSafety.Verdict.Blocked -> {
        DbResultsToolWindow.tabFor(project, file, dataSource).showError(verdict.message)
        return
      }
      is SqlSafety.Verdict.Confirm -> {
        val ok = Messages.showOkCancelDialog(project, verdict.message, PhantomDbBundle.message("db.safety.title"),
                                             PhantomDbBundle.message("db.safety.run"), Messages.getCancelButton(), Messages.getWarningIcon())
        if (ok != Messages.OK) return
      }
      SqlSafety.Verdict.Ok -> {}
    }
    val panel = DbResultsToolWindow.tabFor(project, file, dataSource)
    val schema = executionSchema(project, file)
    panel.schema = schema
    panel.showRunning(SqlSafety.preview(sql.first()) + if (sql.size > 1) " (+${sql.size - 1})" else "")
    val job = DbConnectionService.getInstance(project).launch {
      try {
        val outcomes = DbExecutor.execute(project, dataSource, sql, schema = schema)
        withContext(Dispatchers.EDT) { if (present != null) present(panel, outcomes) else panel.showOutcomes(outcomes) }
      }
      catch (e: CancellationException) {
        withContext(Dispatchers.EDT + NonCancellable) { panel.showError(PhantomDbBundle.message("db.run.cancelled")) }
        throw e
      }
      catch (e: java.sql.SQLException) {
        withContext(Dispatchers.EDT) { panel.showError(DbExecutor.message(e)) }
      }
      catch (e: DbException) {
        withContext(Dispatchers.EDT) { panel.showError(e.message.orEmpty()) }
      }
      catch (e: Exception) {
        // a bug on our side, not a database answer: report it, and still tell the user something
        com.intellij.openapi.diagnostic.logger<DbRunner>().error(e)
        withContext(Dispatchers.EDT + NonCancellable) { panel.showError(e.message ?: e.javaClass.simpleName) }
      }
    }
    panel.job = job
  }
}

