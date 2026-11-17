// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import dev.phantom.db.core.*
import dev.phantom.db.core.ui.DbPasswordPrompt
import java.io.File
import java.nio.file.Path

/**
 * Dump to File… and Restore from File… of the Database tree, with the database's own client tools: `mysqldump` / `mysql`
 * (`mariadb-dump` / `mariadb` when those are not found) for MySQL and MariaDB, `pg_dump` / `psql` for PostgreSQL. Other
 * databases have neither action.
 *
 * The command lines are built by pure functions. The password goes only into the environment of the process
 * (`MYSQL_PWD` / `PGPASSWORD`), never into the arguments. SSL options are not mapped, and a data source behind an SSH
 * tunnel is refused (the tools would connect to the host of the URL, not through the tunnel).
 */
object DbDump {
  const val NOTIFICATION_GROUP: String = "Phantom Database"

  /** Where a data source is: [database] is `null` when the URL has none, [port] when the URL has none (the tool's default). */
  data class Endpoint(val host: String, val port: Int?, val database: String?)

  /** The client programs, by their usual name, then the MariaDB name that stands in for it. */
  enum class Tool(val executable: String, val alternative: String?) {
    MYSQLDUMP("mysqldump", "mariadb-dump"),
    MYSQL("mysql", "mariadb"),
    PG_DUMP("pg_dump", null),
    PSQL("psql", null);

    val key: String get() = "phantom.db.tool.$executable"
  }

  private val COMMON_DIRECTORIES = listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/local/mysql/bin",
                                          "/Applications/Postgres.app/Contents/Versions/latest/bin")

  fun endpoint(url: String): Endpoint? {
    val address = runCatching { DbTunnelUrls.address(url) }.getOrNull() ?: return null
    val rest = url.substring(url.indexOf("//") + 2)
    val pathStart = rest.indexOfFirst { it in "/;?#" }
    val database = if (pathStart >= 0 && rest[pathStart] == '/') {
      rest.substring(pathStart + 1).takeWhile { it !in "?;#" }.takeIf { it.isNotEmpty() }
    }
    else null
    return Endpoint(address.host, address.port, database)
  }

  private fun isMysql(ds: DbDataSource): Boolean = ds.dialect().let { it == Dbms.MYSQL || it == Dbms.MARIADB }

  fun supported(ds: DbDataSource): Boolean {
    val dbms = ds.dialect()
    return (dbms == Dbms.MYSQL || dbms == Dbms.MARIADB || dbms == Dbms.POSTGRESQL) && endpoint(ds.url) != null
  }

  /** The program to run for [tool]: the saved path if it is executable, else the PATH, else where installers put it. */
  fun path(tool: Tool): String? {
    PropertiesComponent.getInstance().getValue(tool.key)?.takeIf { File(it).canExecute() }?.let { return it }
    val names = listOfNotNull(tool.executable, tool.alternative)
    for (name in names) PathEnvironmentVariableUtil.findInPath(name)?.let { return it.path }
    for (name in names) for (dir in COMMON_DIRECTORIES) File(dir, name).takeIf { it.canExecute() }?.let { return it.path }
    return null
  }

  fun setPath(tool: Tool, path: String?) {
    if (path.isNullOrBlank()) PropertiesComponent.getInstance().unsetValue(tool.key) else PropertiesComponent.getInstance().setValue(tool.key, path)
  }

  private fun GeneralCommandLine.withPassword(variable: String, password: String?): GeneralCommandLine {
    if (!password.isNullOrEmpty()) withEnvironment(variable, password)
    return this
  }

  /**
   * [schema]: the node's schema, `null` on the data source. MySQL and MariaDB use it as the database; a data source whose
   * URL has no database dumps `--all-databases`. PostgreSQL adds `--schema`.
   */
  fun dumpCommand(ds: DbDataSource, schema: String?, out: Path, exe: String, password: String?): GeneralCommandLine {
    val end = endpoint(ds.url) ?: throw DbException(PhantomDbBundle.message("db.dump.no.address", ds.url))
    return if (isMysql(ds)) {
      val database = schema ?: end.database
      // TCP even for localhost, where the client would otherwise take the local socket and not the port (a container's)
      val cmd = GeneralCommandLine(exe).withParameters("--protocol=TCP", "--host=${end.host}")
      end.port?.let { cmd.addParameter("--port=$it") }
      if (ds.user.isNotEmpty()) cmd.addParameter("--user=${ds.user}")
      cmd.addParameters("--routines", "--triggers", "--single-transaction", "--result-file=$out")
      if (database == null) cmd.addParameter("--all-databases") else cmd.addParameter(database)
      cmd.withPassword("MYSQL_PWD", password)
    }
    else {
      val cmd = GeneralCommandLine(exe).withParameters("--host=${end.host}")
      end.port?.let { cmd.addParameter("--port=$it") }
      if (ds.user.isNotEmpty()) cmd.addParameter("--username=${ds.user}")
      end.database?.let { cmd.addParameter("--dbname=$it") }
      if (schema != null) cmd.addParameter("--schema=$schema")
      cmd.addParameter("--file=$out")
      cmd.withPassword("PGPASSWORD", password)
    }
  }

  /** MySQL reads the file from standard input; PostgreSQL's `psql` stops at the first error. */
  fun restoreCommand(ds: DbDataSource, schema: String?, input: Path, exe: String, password: String?): GeneralCommandLine {
    val end = endpoint(ds.url) ?: throw DbException(PhantomDbBundle.message("db.dump.no.address", ds.url))
    return if (isMysql(ds)) {
      val cmd = GeneralCommandLine(exe).withParameters("--protocol=TCP", "--host=${end.host}")
      end.port?.let { cmd.addParameter("--port=$it") }
      if (ds.user.isNotEmpty()) cmd.addParameter("--user=${ds.user}")
      (schema ?: end.database)?.let { cmd.addParameter(it) }
      cmd.withInput(input.toFile()).withPassword("MYSQL_PWD", password)
    }
    else {
      val cmd = GeneralCommandLine(exe).withParameters("--host=${end.host}")
      end.port?.let { cmd.addParameter("--port=$it") }
      if (ds.user.isNotEmpty()) cmd.addParameter("--username=${ds.user}")
      end.database?.let { cmd.addParameter("--dbname=$it") }
      cmd.addParameters("-v", "ON_ERROR_STOP=1", "--file=$input")
      cmd.withPassword("PGPASSWORD", password)
    }
  }

  private fun tool(ds: DbDataSource, restore: Boolean): Tool = when {
    isMysql(ds) -> if (restore) Tool.MYSQL else Tool.MYSQLDUMP
    else -> if (restore) Tool.PSQL else Tool.PG_DUMP
  }

  private fun notify(project: Project, type: NotificationType, message: String, configure: (com.intellij.notification.Notification) -> Unit = {}) {
    val notification = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
      .createNotification(PhantomDbBundle.message("db.dump.title"), message, type)
    configure(notification)
    notification.notify(project)
  }

  /** On the EDT: chooses the file, asks before a restore, then runs the tool in the background. */
  fun run(project: Project, ds: DbDataSource, schema: String?, restore: Boolean) {
    if (ds.sshEnabled) {
      notify(project, NotificationType.WARNING, PhantomDbBundle.message("db.dump.ssh", ds.name))
      return
    }
    val tool = tool(ds, restore)
    val exe = path(tool)
    if (exe == null) {
      notify(project, NotificationType.ERROR, PhantomDbBundle.message("db.dump.tool.missing", tool.executable)) { n ->
        n.addAction(NotificationAction.createSimpleExpiring(PhantomDbBundle.message("db.dump.tool.choose")) { chooseTool(project, tool) })
      }
      return
    }
    val file: File = if (restore) {
      val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("sql")
        .withTitle(PhantomDbBundle.message("db.restore.choose.title"))
      FileChooser.chooseFile(descriptor, project, null)?.let { File(it.path) } ?: return
    }
    else {
      val descriptor = FileSaverDescriptor(PhantomDbBundle.message("db.dump.choose.title"), PhantomDbBundle.message("db.dump.choose.description"), "sql")
      val name = (schema ?: endpoint(ds.url)?.database ?: ds.name).ifEmpty { "dump" } + ".sql"
      FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project).save(null as com.intellij.openapi.vfs.VirtualFile?, name)?.file ?: return
    }
    if (restore) {
      val target = schema ?: endpoint(ds.url)?.database ?: ds.name
      val answer = Messages.showYesNoDialog(project, PhantomDbBundle.message("db.restore.confirm", file.name, target),
                                            PhantomDbBundle.message("db.restore.confirm.title"), Messages.getWarningIcon())
      if (answer != Messages.YES) return
    }
    val title = if (restore) PhantomDbBundle.message("db.restore.progress", ds.name) else PhantomDbBundle.message("db.dump.progress", ds.name)
    object : Task.Backgroundable(project, title, true) {
      override fun run(indicator: ProgressIndicator) {
        indicator.isIndeterminate = true
        execute(project, ds, schema, file, restore, exe, indicator)
      }
    }.queue()
  }

  private fun chooseTool(project: Project, tool: Tool) {
    val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor().withTitle(PhantomDbBundle.message("db.dump.tool.choose.title", tool.executable))
    FileChooser.chooseFile(descriptor, project, null)?.let { setPath(tool, it.path) }
  }

  /** Off the EDT. Reads the password, runs the process and reports the result as a notification. */
  private fun execute(project: Project, ds: DbDataSource, schema: String?, file: File, restore: Boolean, exe: String, indicator: ProgressIndicator) {
    var password: String? = null
    if (ds.authKind == DbAuthKind.PASSWORD) {
      password = DbDataSourceService.getInstance(project).password(ds.id)?.takeIf { it.isNotEmpty() }
      if (password == null && ds.credentialStorage == DbPasswordStorage.NEVER) {
        val typed = DbPasswordPrompt.ask(project, ds, null)
        if (typed == null) {
          notify(project, NotificationType.WARNING, PhantomDbBundle.message("db.password.cancelled"))
          return
        }
        password = typed.password.takeIf { it.isNotEmpty() }
      }
    }
    val cmd = try {
      if (restore) restoreCommand(ds, schema, file.toPath(), exe, password) else dumpCommand(ds, schema, file.toPath(), exe, password)
    }
    catch (e: DbException) {
      notify(project, NotificationType.ERROR, e.message ?: "")
      return
    }
    val output: ProcessOutput = try {
      CapturingProcessHandler(cmd).runProcessWithProgressIndicator(indicator)
    }
    catch (e: Exception) {
      notify(project, NotificationType.ERROR, PhantomDbBundle.message("db.dump.failed.start", e.message ?: e.javaClass.simpleName))
      return
    }
    when {
      output.isCancelled -> notify(project, NotificationType.WARNING, PhantomDbBundle.message("db.dump.cancelled"))
      output.exitCode != 0 -> {
        val tail = output.stderr.lines().filter { it.isNotBlank() }.takeLast(5).joinToString("\n")
        notify(project, NotificationType.ERROR, PhantomDbBundle.message("db.dump.failed", output.exitCode, tail))
      }
      restore -> notify(project, NotificationType.INFORMATION, PhantomDbBundle.message("db.restore.done", file.name))
      else -> notify(project, NotificationType.INFORMATION, PhantomDbBundle.message("db.dump.done", file.path, file.length()))
    }
  }
}
