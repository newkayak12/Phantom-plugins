// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import com.intellij.ide.util.PropertiesComponent
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
 * Consoles are plain `.sql` files under the IDE config directory, one folder per data source, so they survive restarts
 * and the folder name tells which data source a console belongs to. Any other `.sql` file can be attached to a data
 * source too; that choice is kept per file in the workspace.
 */
object DbConsoles {
  private const val KEY_PREFIX = "phantom.db.dataSource:"

  fun root(): Path = PathManager.getConfigDir().resolve("phantom-db/consoles")

  fun consoleDataSourceId(file: VirtualFile): String? {
    val path = runCatching { file.toNioPath() }.getOrNull() ?: return null
    val parent = path.parent ?: return null
    return if (parent.parent == root()) parent.fileName.toString() else null
  }

  fun isConsole(file: VirtualFile): Boolean = consoleDataSourceId(file) != null

  fun dataSourceFor(project: Project, file: VirtualFile?): DbDataSource? {
    if (file == null) return null
    val service = DbDataSourceService.getInstance(project)
    consoleDataSourceId(file)?.let { return service.find(it) }
    return service.find(PropertiesComponent.getInstance(project).getValue(KEY_PREFIX + file.url))
  }

  fun attach(project: Project, file: VirtualFile, dataSourceId: String?) {
    PropertiesComponent.getInstance(project).setValue(KEY_PREFIX + file.url, dataSourceId)
    EditorNotifications.getInstance(project).updateNotifications(file)
  }

  /** The data source's console file, created on first use. */
  fun consoleFile(dataSource: DbDataSource): VirtualFile? {
    val dir = root().resolve(dataSource.id)
    val name = FileUtil.sanitizeFileName(dataSource.name.ifBlank { dataSource.dialect().displayName }, false) + ".sql"
    val existing = Files.list(dir.also { Files.createDirectories(it) }).use { s -> s.filter { it.toString().endsWith(".sql") }.findFirst().orElse(null) }
    val path = existing ?: dir.resolve(name).also { Files.writeString(it, "") }
    return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
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
          prepare: ((DbDataSource) -> List<String>?)? = null) {
    val sql = statements.map { it.trim() }.filter { it.isNotEmpty() }
    if (sql.isEmpty()) return
    fun go(dataSource: DbDataSource) {
      start(project, file, dataSource, if (prepare == null) sql else prepare(dataSource) ?: return)
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
      override fun getIconFor(value: DbDataSource): Icon = value.dialect().icon
      override fun onChosen(selectedValue: DbDataSource, finalChoice: Boolean): PopupStep<*>? {
        DbConsoles.attach(project, file, selectedValue.id)
        onChosen(selectedValue)
        return FINAL_CHOICE
      }
    }
    val popup = JBPopupFactory.getInstance().createListPopup(step)
    if (owner != null && owner.isShowing) popup.showInCenterOf(owner) else popup.showCenteredInCurrentWindow(project)
  }

  fun start(project: Project, file: VirtualFile, dataSource: DbDataSource, statements: List<String>) {
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
    panel.showRunning(SqlSafety.preview(sql.first()) + if (sql.size > 1) " (+${sql.size - 1})" else "")
    val job = DbConnectionService.getInstance(project).launch {
      try {
        val outcomes = DbExecutor.execute(project, dataSource, sql)
        withContext(Dispatchers.EDT) { panel.showOutcomes(outcomes) }
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

