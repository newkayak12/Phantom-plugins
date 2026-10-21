// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import com.intellij.openapi.Disposable
import dev.phantom.db.core.dialect
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import dev.phantom.db.core.*
import dev.phantom.db.core.drivers.DbDriverActions
import dev.phantom.db.core.drivers.DbDriverDefinition
import dev.phantom.db.core.drivers.DbDriverRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLClassLoader
import java.nio.file.Path
import java.sql.*
import java.util.Properties
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.minutes

class DbException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A column of a result set. */
data class DbColumn(val name: String, val sqlType: Int, val typeName: String?, val className: String?)

/** What one statement produced. */
sealed interface DbOutcome {
  val sql: String
  val elapsedMs: Long

  class Rows(override val sql: String, val columns: List<DbColumn>, val rows: List<Array<Any?>>, val truncated: Boolean,
             override val elapsedMs: Long) : DbOutcome

  class Updated(override val sql: String, val count: Long, override val elapsedMs: Long) : DbOutcome

  class Failed(override val sql: String, val message: String, override val elapsedMs: Long) : DbOutcome
}

/**
 * JDBC connections of the project's data sources. Each driver runs in its own class loader, created from the driver
 * jars with the JDK platform loader as parent, and is called directly instead of through `DriverManager`.
 *
 * A data source has at most two open connections: one for consoles and one for the schema tree, so browsing does not
 * wait for a long query. A connection closes after [IDLE] without use; nothing stays open after the project closes.
 */
@Service(Service.Level.PROJECT)
class DbConnectionService(private val project: Project, private val cs: CoroutineScope) : Disposable {
  enum class Purpose { CONSOLE, METADATA }

  private class Session {
    val mutex = Mutex()
    var connection: Connection? = null
    var idleClose: Job? = null
    /** The schema a console chose for [connection]; null while it is in the one it opened in. */
    var schema: String? = null
    /** The schema [connection] opened in, read before the first switch so "default" can go back to it. */
    var openedIn: String? = null
  }

  private val loaders = ConcurrentHashMap<List<Path>, URLClassLoader>()
  private val drivers = ConcurrentHashMap<Pair<List<Path>, String>, Driver>()
  private val sessions = ConcurrentHashMap<Pair<String, Purpose>, Session>()

  /**
   * Runs [block] with the data source's connection for [purpose], opening it when needed. Calls are serialized.
   * [schema]: the schema (catalog in MySQL) to run in first, "" for the one the connection opened in, null to leave it.
   */
  suspend fun <T> withConnection(dataSource: DbDataSource, purpose: Purpose, schema: String? = null, block: suspend (Connection) -> T): T {
    val session = sessions.computeIfAbsent(dataSource.id to purpose) { Session() }
    return session.mutex.withLock {
      session.idleClose?.cancel()
      try {
        withContext(Dispatchers.IO) {
          val connection = session.connection?.takeUnless { it.isClosed } ?: open(dataSource, null).also {
            session.connection = it
            session.schema = null
            session.openedIn = null
          }
          try {
            if (schema != null) useSchema(dataSource, session, connection, schema)
            block(connection)
          }
          catch (e: SQLException) {
            if (!runCatching { connection.isValid(2) }.getOrDefault(false)) {
              closeQuietly(session)
              // a lost connection takes its open transaction with it
              DbPendingChanges.getInstanceIfCreated(project)?.discard(dataSource.id)
            }
            throw e
          }
        }
      }
      finally {
        // an open transaction keeps the connection: closing it would silently roll the changes back
        val pending = purpose == Purpose.CONSOLE && DbPendingChanges.getInstanceIfCreated(project)?.hasPending(dataSource.id) == true
        if (session.connection != null && !pending) {
          session.idleClose = cs.launch {
            delay(IDLE)
            session.mutex.withLock { closeQuietly(session) }
          }
        }
      }
    }
  }

  /** Runs [block] on IO in the service scope, which the project's closing cancels. */
  fun launch(block: suspend CoroutineScope.() -> Unit): Job = cs.launch(Dispatchers.IO, block = block)

  fun isConnected(dataSourceId: String): Boolean =
    Purpose.entries.any { sessions[dataSourceId to it]?.connection?.let { c -> runCatching { !c.isClosed }.getOrDefault(false) } == true }

  /**
   * Commits or rolls back the console connection's open transaction. Fails when the connection is gone, since its
   * changes went with it.
   */
  suspend fun endTransaction(dataSource: DbDataSource, commit: Boolean) {
    val session = sessions[dataSource.id to Purpose.CONSOLE]
    if (session == null) {
      DbPendingChanges.getInstanceIfCreated(project)?.discard(dataSource.id)
      throw DbException(PhantomDbBundle.message("db.changes.connection.lost", dataSource.name))
    }
    session.mutex.withLock {
      withContext(Dispatchers.IO) {
        val connection = session.connection?.takeUnless { runCatching { it.isClosed }.getOrDefault(true) }
        if (connection == null) {
          DbPendingChanges.getInstanceIfCreated(project)?.discard(dataSource.id)
          throw DbException(PhantomDbBundle.message("db.changes.connection.lost", dataSource.name))
        }
        if (commit) connection.commit() else connection.rollback()
        DbPendingChanges.getInstanceIfCreated(project)?.discard(dataSource.id)
      }
    }
  }

  /**
   * Puts the open console connection, if any, into the data source's commit mode. Switching to auto-commit commits
   * whatever is open, so end the transaction first.
   */
  suspend fun applyCommitMode(dataSource: DbDataSource) {
    val session = sessions[dataSource.id to Purpose.CONSOLE] ?: return
    session.mutex.withLock {
      withContext(Dispatchers.IO) {
        val connection = session.connection?.takeUnless { runCatching { it.isClosed }.getOrDefault(true) } ?: return@withContext
        if (!dataSource.readOnly) connection.autoCommit = !dataSource.manualCommit
      }
    }
  }

  /** Closes the data source's connections, e.g. after it was edited or removed. Uncommitted changes are rolled back. */
  fun disconnect(dataSourceId: String) {
    DbPendingChanges.getInstanceIfCreated(project)?.discard(dataSourceId)
    for (purpose in Purpose.entries) {
      val session = sessions.remove(dataSourceId to purpose) ?: continue
      session.idleClose?.cancel()
      cs.launch(Dispatchers.IO) { session.mutex.withLock { closeQuietly(session) } }
    }
  }

  /** Opens a throwaway connection with [password] and reports the server and driver versions. Blocking. */
  fun testConnection(dataSource: DbDataSource, password: String?): String {
    // the dialog shows the error inline: no balloon on top of it
    open(dataSource, password ?: "", notify = false).use { c ->
      val md = c.metaData
      return PhantomDbBundle.message("db.test.ok", "${md.databaseProductName} ${md.databaseProductVersion}",
                                     "${md.driverName} ${md.driverVersion}")
    }
  }

  /** Blocking. A `null` [password] means the saved one. [notify]: a driver that cannot be found also shows a balloon. */
  fun open(dataSource: DbDataSource, password: String?, notify: Boolean = true): Connection {
    val definition = dataSource.driver()
    if (definition == null) {
      val name = dataSource.driverId?.takeIf { it.isNotEmpty() } ?: dataSource.url
      if (notify) DbDriverActions.notifyMissing(project, dataSource, null, PhantomDbBundle.message("db.driver.unknown", name))
      throw DbException(PhantomDbBundle.message("db.driver.unknown", name))
    }
    val located = DbDrivers.find(project, dataSource)
    if (located == null) {
      val message = PhantomDbBundle.message("db.driver.missing.download", definition.name)
      if (notify) DbDriverActions.notifyMissing(project, dataSource, definition, message)
      throw DbException(message)
    }
    val driver = drivers.computeIfAbsent(located.jars to definition.driverClass) { loadDriver(it.first, it.second) }
    val secret = (password ?: DbDataSourceService.getInstance(project).password(dataSource.id))?.takeIf { it.isNotEmpty() }
    val props = connectionProperties(definition, dataSource, secret)
    // drivers that find others or load SDK parts through the context loader (AWS JDBC Wrapper, AWS SDK) must see their own jars
    val connection = withContextClassLoader(driver.javaClass.classLoader) { driver.connect(connectionUrl(definition, dataSource).trim(), props) }
                     ?: throw DbException(rejectedUrlMessage(definition, dataSource.url, located.description))
    if (dataSource.readOnly) runCatching { connection.isReadOnly = true }
    else if (dataSource.manualCommit) connection.autoCommit = false
    return connection
  }

  private fun loadDriver(jars: List<Path>, driverClass: String): Driver {
    val loader = loaders.computeIfAbsent(jars) { URLClassLoader(jars.map { it.toUri().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader()) }
    return withContextClassLoader(loader) {
      try {
        Class.forName(driverClass, true, loader).getDeclaredConstructor().newInstance() as Driver
      }
      catch (_: ClassNotFoundException) {
        // older or repackaged drivers: use whatever the jar declares in META-INF/services
        ServiceLoader.load(Driver::class.java, loader).firstOrNull { it.javaClass.classLoader == loader }
        ?: throw DbException(PhantomDbBundle.message("db.driver.no.class", driverClass, jars.joinToString()))
      }
    }
  }

  /** Switches [connection] to [schema] ("" = where it opened) unless it is there already. */
  private fun useSchema(dataSource: DbDataSource, session: Session, connection: Connection, schema: String) {
    if (schema == session.schema.orEmpty()) return
    val catalogs = switchesCatalog(dataSource.dialect(), connection.metaData)
    val openedIn = session.openedIn ?: (if (catalogs) connection.catalog else connection.schema).orEmpty().also { session.openedIn = it }
    val target = schema.ifEmpty { openedIn }
    try {
      if (target.isNotEmpty()) if (catalogs) connection.catalog = target else connection.schema = target
    }
    catch (_: AbstractMethodError) {
      // a driver older than JDBC 4.1
      throw DbException(PhantomDbBundle.message("db.schema.unsupported", target))
    }
    session.schema = schema.ifEmpty { null }
  }

  private fun closeQuietly(session: Session) {
    session.idleClose = null
    val c = session.connection ?: return
    session.connection = null
    runCatching { if (!c.autoCommit) c.rollback() }
    runCatching { c.close() }
  }

  override fun dispose() {
    for (session in sessions.values) {
      session.idleClose?.cancel()
      session.connection?.let {
        runCatching { if (!it.autoCommit) it.rollback() }
        runCatching { it.close() }
      }
    }
    sessions.clear()
    for (loader in loaders.values) {
      // MySQL Connector/J keeps a cleanup thread per loader; stop it so the loader can go
      runCatching { loader.loadClass("com.mysql.cj.jdbc.AbandonedConnectionCleanupThread").getMethod("checkedShutdown").invoke(null) }
      runCatching { loader.close() }
    }
    loaders.clear()
    drivers.clear()
  }

  companion object {
    val IDLE = 10.minutes
    internal const val LOGIN_TIMEOUT_SECONDS = 15

    fun getInstance(project: Project): DbConnectionService = project.service()
  }
}

/**
 * Whether choosing a console schema sets the connection's catalog (MySQL, MariaDB; another JDBC driver that has
 * catalogs but no schemas) rather than its schema.
 */
internal fun switchesCatalog(dialect: Dbms, md: DatabaseMetaData): Boolean = when {
  dialect.usesCatalogs -> true
  dialect != Dbms.GENERIC -> false
  else -> runCatching { !md.supportsSchemasInDataManipulation() && md.supportsCatalogsInDataManipulation() }.getOrDefault(false)
}

/** Runs [block] with [loader] as this thread's context class loader, then puts the previous one back. */
internal inline fun <T> withContextClassLoader(loader: ClassLoader?, block: () -> T): T {
  val thread = Thread.currentThread()
  val previous = thread.contextClassLoader
  thread.contextClassLoader = loader
  try {
    return block()
  }
  finally {
    thread.contextClassLoader = previous
  }
}

/** Driver defaults, then login timeouts, then the data source's own properties, then the authentication. */
internal fun DbConnectionService.Companion.connectionProperties(definition: DbDriverDefinition, dataSource: DbDataSource, password: String?): Properties {
  val props = Properties()
  props.putAll(definition.defaultProperties)
  // fail in seconds, not minutes, when the host does not answer; a value in the URL still wins
  when (definition.dialect) {
    Dbms.MYSQL, Dbms.MARIADB -> props["connectTimeout"] = (DbConnectionService.LOGIN_TIMEOUT_SECONDS * 1000).toString()
    Dbms.POSTGRESQL -> props["connectTimeout"] = DbConnectionService.LOGIN_TIMEOUT_SECONDS.toString()
    Dbms.H2, Dbms.GENERIC -> {}
  }
  props.putAll(dataSource.properties)
  when (dataSource.authKind) {
    DbAuthKind.PASSWORD -> {
      if (dataSource.user.isNotEmpty()) props["user"] = dataSource.user
      if (password != null) props["password"] = password
    }
    DbAuthKind.NONE -> {}
    DbAuthKind.AWS_IAM -> {
      if (dataSource.user.isNotEmpty()) props["user"] = dataSource.user
      for ((key, template) in definition.iamProperties) {
        val value = template.replace("{profile}", dataSource.awsProfile.trim()).replace("{region}", dataSource.awsRegion.trim())
        if (value.isNotBlank()) props[key] = value
      }
    }
  }
  return props
}

internal fun connectionUrl(definition: DbDriverDefinition, dataSource: DbDataSource): String {
  val url = dataSource.url
  val from = definition.iamUrlFrom
  val to = definition.iamUrlTo
  return if (dataSource.authKind == DbAuthKind.AWS_IAM && !from.isNullOrEmpty() && to != null && url.startsWith(from)) to + url.removePrefix(from) else url
}

/** A driver turned [url] down: name the driver the URL is written for, or else the form this driver expects. */
internal fun rejectedUrlMessage(definition: DbDriverDefinition, url: String, driverDescription: String): String {
  val rejected = PhantomDbBundle.message("db.driver.url.rejected", url, driverDescription)
  val other = DbDriverRegistry.getInstance().forUrl(url)?.takeIf { it.id != definition.id }
  val hint = if (other != null) PhantomDbBundle.message("db.driver.url.other", other.name, definition.urlTemplate)
             else PhantomDbBundle.message("db.driver.url.expected", definition.urlTemplate)
  return "$rejected. $hint"
}

/** Runs SQL text on a data source's console connection. */
object DbExecutor {
  private val LOG = logger<DbExecutor>()
  private const val LOB_LIMIT = 4096

  /**
   * Runs [statements] in order and stops at the first failure. Cancelling the coroutine cancels the running statement.
   * [schema] as in [DbConnectionService.withConnection]; a schema that cannot be chosen throws its [SQLException].
   */
  suspend fun execute(project: Project, dataSource: DbDataSource, statements: List<String>, schema: String? = null,
                      onOutcome: suspend (DbOutcome) -> Unit = {}): List<DbOutcome> {
    val outcomes = ArrayList<DbOutcome>()
    DbConnectionService.getInstance(project).withConnection(dataSource, DbConnectionService.Purpose.CONSOLE, schema) { connection ->
      for (sql in statements) {
        val outcome = runOne(connection, dataSource, sql)
        outcomes.addAll(outcome)
        outcome.forEach { DbQueryHistory.getInstance(project).record(dataSource, it) }
        outcome.forEach { onOutcome(it) }
        if (outcome.any { it is DbOutcome.Failed }) break
        if (outcome.any { it is DbOutcome.Updated } && SqlSafety.isDdl(sql)) {
          DbMetadataCache.getInstanceIfCreated(project)?.invalidate(dataSource.id)
        }
        if (!connection.autoCommit) DbPendingChanges.getInstance(project).afterStatement(dataSource, sql, outcome)
      }
    }
    return outcomes
  }

  /**
   * Runs one prepared statement from a grid edit on the console connection, so in a manual-commit data source it joins
   * the open transaction and shows up in Database Changes under its [DbStatement.display] text.
   */
  suspend fun execute(project: Project, dataSource: DbDataSource, statement: DbStatement): DbOutcome =
    DbConnectionService.getInstance(project).withConnection(dataSource, DbConnectionService.Purpose.CONSOLE) { connection ->
      val started = System.nanoTime()
      val outcome = withContext(Dispatchers.IO) {
        try {
          connection.prepareStatement(statement.sql).use { ps ->
            if (dataSource.queryTimeoutSeconds > 0) ps.queryTimeout = dataSource.queryTimeoutSeconds
            statement.params.forEachIndexed { i, p ->
              val type = DbEditTargets.bindType(p.sqlType)
              if (p.value == null) ps.setNull(i + 1, type) else ps.setObject(i + 1, p.value, type)
            }
            DbOutcome.Updated(statement.display, ps.executeLargeUpdateOrPlain(), (System.nanoTime() - started) / 1_000_000)
          }
        }
        catch (e: SQLException) {
          DbOutcome.Failed(statement.display, message(e), (System.nanoTime() - started) / 1_000_000)
        }
      }
      if (!connection.autoCommit) DbPendingChanges.getInstance(project).afterStatement(dataSource, outcome.sql, listOf(outcome))
      outcome
    }

  private fun java.sql.PreparedStatement.executeLargeUpdateOrPlain(): Long =
    try {
      executeLargeUpdate()
    }
    catch (_: UnsupportedOperationException) {
      executeUpdate().toLong()
    }
    catch (_: SQLFeatureNotSupportedException) {
      executeUpdate().toLong()
    }

  private suspend fun runOne(connection: Connection, dataSource: DbDataSource, sql: String): List<DbOutcome> = withContext(Dispatchers.IO) {
    val started = System.nanoTime()
    fun elapsed() = (System.nanoTime() - started) / 1_000_000
    val statement = try {
      connection.createStatement()
    }
    catch (e: SQLException) {
      return@withContext listOf(DbOutcome.Failed(sql, message(e), elapsed()))
    }
    val finished = AtomicBoolean()
    // a blocked JDBC call does not see coroutine cancellation; Statement.cancel() from another thread stops it
    val canceller = launch(start = CoroutineStart.UNDISPATCHED) {
      try {
        awaitCancellation()
      }
      finally {
        if (!finished.get()) runCatching { statement.cancel() }
      }
    }
    try {
      statement.maxRows = dataSource.maxRows + 1
      if (dataSource.queryTimeoutSeconds > 0) statement.queryTimeout = dataSource.queryTimeoutSeconds
      val outcomes = ArrayList<DbOutcome>()
      var isResultSet = statement.execute(sql)
      while (true) {
        if (isResultSet) {
          statement.resultSet.use { outcomes.add(read(sql, it, dataSource.maxRows, elapsed())) }
        }
        else {
          val count = statement.largeUpdateCountOrPlain()
          if (count < 0) break
          outcomes.add(DbOutcome.Updated(sql, count, elapsed()))
        }
        isResultSet = statement.moreResults
      }
      if (outcomes.isEmpty()) outcomes.add(DbOutcome.Updated(sql, 0, elapsed()))
      outcomes
    }
    catch (e: SQLException) {
      ensureActive()
      listOf(DbOutcome.Failed(sql, message(e), elapsed()))
    }
    finally {
      finished.set(true)
      canceller.cancel()
      runCatching { statement.close() }
    }
  }

  private fun Statement.largeUpdateCountOrPlain(): Long =
    try {
      largeUpdateCount
    }
    catch (_: UnsupportedOperationException) {
      updateCount.toLong()
    }
    catch (_: SQLFeatureNotSupportedException) {
      updateCount.toLong()
    }

  private fun read(sql: String, rs: ResultSet, maxRows: Int, elapsedSoFar: Long): DbOutcome.Rows {
    val started = System.nanoTime()
    val md = rs.metaData
    val columns = (1..md.columnCount).map { i ->
      DbColumn(md.getColumnLabel(i).ifEmpty { md.getColumnName(i) }, md.getColumnType(i), md.getColumnTypeName(i),
               runCatching { md.getColumnClassName(i) }.getOrNull())
    }
    val rows = ArrayList<Array<Any?>>()
    var truncated = false
    while (rs.next()) {
      if (rows.size == maxRows) {
        truncated = true
        break
      }
      rows.add(Array(columns.size) { i -> value(rs, i + 1) })
    }
    return DbOutcome.Rows(sql, columns, rows, truncated, elapsedSoFar + (System.nanoTime() - started) / 1_000_000)
  }

  /** JDK types stay as they are (the grid formats them); LOBs are cut to [LOB_LIMIT]; driver types become text. */
  private fun value(rs: ResultSet, i: Int): Any? {
    val v = try {
      rs.getObject(i)
    }
    catch (e: SQLException) {
      LOG.debug(e)
      return runCatching { rs.getString(i) }.getOrNull()
    }
    return when (v) {
      null -> null
      is Clob -> v.getSubString(1, minOf(v.length(), LOB_LIMIT.toLong()).toInt())
      is Blob -> v.getBytes(1, minOf(v.length(), LOB_LIMIT.toLong()).toInt())
      is ByteArray -> if (v.size > LOB_LIMIT) v.copyOf(LOB_LIMIT) else v
      is java.sql.Array -> runCatching { (v.array as? Array<*>)?.contentToString() }.getOrNull() ?: v.toString()
      is String, is Number, is Boolean, is java.util.Date, is java.time.temporal.Temporal, is java.util.UUID -> v
      else -> if (v.javaClass.classLoader == null) v else v.toString()
    }
  }

  internal fun message(e: SQLException): String {
    val parts = generateSequence<Throwable>(e) { it.cause ?: (it as? SQLException)?.nextException }
      .take(4).mapNotNull { it.message?.trim()?.takeIf(String::isNotEmpty) }.distinct().toList()
    val state = e.sqlState?.let { " [$it]" }.orEmpty()
    return (parts.firstOrNull() ?: e.javaClass.simpleName) + state
  }
}

data class DbSchemaRef(val dataSourceId: String, val catalog: String?, val schema: String?) {
  val name: String get() = schema ?: catalog ?: ""
}

data class DbTableRef(val dataSourceId: String, val catalog: String?, val schema: String?, val name: String, val kind: String) {
  val isView: Boolean get() = kind.contains("VIEW", ignoreCase = true)
}

data class DbColumnInfo(val name: String, val typeName: String, val nullable: Boolean, val primaryKey: Boolean)

/**
 * Schema metadata the tree has loaded, kept in memory only. Entries are filled when a node is expanded and dropped on
 * Refresh or after DDL from a console; completion reads this cache and never connects.
 */
@Service(Service.Level.PROJECT)
class DbMetadataCache(private val project: Project) {
  private val schemas = ConcurrentHashMap<String, List<DbSchemaRef>>()
  private val tables = ConcurrentHashMap<DbSchemaRef, List<DbTableRef>>()
  private val columns = ConcurrentHashMap<DbTableRef, List<DbColumnInfo>>()

  suspend fun schemas(dataSource: DbDataSource): List<DbSchemaRef> = schemas[dataSource.id] ?: metadata(dataSource) { md, c ->
    val current = if (dataSource.dialect().usesCatalogs) c.catalog else runCatching { c.schema }.getOrNull()
    val all = if (dataSource.dialect().usesCatalogs) {
      md.catalogs.use { rs -> generateSequence { if (rs.next()) DbSchemaRef(dataSource.id, rs.getString(1), null) else null }.toList() }
    }
    else {
      md.schemas.use { rs ->
        generateSequence { if (rs.next()) DbSchemaRef(dataSource.id, runCatching { rs.getString("TABLE_CATALOG") }.getOrNull(), rs.getString("TABLE_SCHEM")) else null }.toList()
      }
    }
    all.filter { it.name.lowercase() !in SYSTEM_SCHEMAS }
      .sortedWith(compareBy<DbSchemaRef> { !it.name.equals(current, ignoreCase = true) }.thenBy { it.name.lowercase() })
  }.also { schemas[dataSource.id] = it }

  suspend fun tables(dataSource: DbDataSource, schema: DbSchemaRef): List<DbTableRef> = tables[schema] ?: metadata(dataSource) { md, _ ->
    md.getTables(schema.catalog, schema.schema?.let { escape(md, it) }, "%", null).use { rs ->
      generateSequence {
        if (rs.next()) DbTableRef(dataSource.id, rs.getString("TABLE_CAT"), rs.getString("TABLE_SCHEM"), rs.getString("TABLE_NAME"), rs.getString("TABLE_TYPE") ?: "TABLE")
        else null
      }.filter { (it.kind.contains("TABLE", true) || it.kind.contains("VIEW", true)) && !it.kind.contains("SYSTEM", true) }
        .toList()
    }.sortedWith(compareBy<DbTableRef> { it.isView }.thenBy { it.name.lowercase() })
  }.also { tables[schema] = it }

  suspend fun columns(dataSource: DbDataSource, table: DbTableRef): List<DbColumnInfo> = columns[table] ?: metadata(dataSource) { md, _ ->
    val keys = runCatching {
      md.getPrimaryKeys(table.catalog, table.schema, table.name).use { rs ->
        generateSequence { if (rs.next()) rs.getString("COLUMN_NAME") else null }.toSet()
      }
    }.getOrDefault(emptySet())
    md.getColumns(table.catalog, table.schema?.let { escape(md, it) }, escape(md, table.name), "%").use { rs ->
      generateSequence {
        if (!rs.next()) return@generateSequence null
        // getColumns takes patterns: `_` in a name matches any character, so keep exact matches only
        if (rs.getString("TABLE_NAME") != table.name) return@generateSequence DbColumnInfo("", "", true, false)
        val name = rs.getString("COLUMN_NAME")
        val size = rs.getInt("COLUMN_SIZE").takeUnless { rs.wasNull() }
        val type = rs.getString("TYPE_NAME").orEmpty()
        val typeText = if (size != null && type.uppercase() in SIZED_TYPES) "$type($size)" else type
        DbColumnInfo(name, typeText, rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls, name in keys)
      }.filter { it.name.isNotEmpty() }.toList()
    }
  }.also { columns[table] = it }

  fun cachedTables(dataSourceId: String): List<DbTableRef> = tables.filterKeys { it.dataSourceId == dataSourceId }.values.flatten()

  fun cachedColumns(table: DbTableRef): List<DbColumnInfo>? = columns[table]

  fun cachedSchemas(dataSourceId: String): List<DbSchemaRef>? = schemas[dataSourceId]

  fun hasCachedTables(schema: DbSchemaRef): Boolean = tables.containsKey(schema)

  fun invalidate(dataSourceId: String) {
    schemas.remove(dataSourceId)
    tables.keys.removeIf { it.dataSourceId == dataSourceId }
    columns.keys.removeIf { it.dataSourceId == dataSourceId }
    DbObjectCache.getInstanceIfCreated(project)?.invalidate(dataSourceId)
  }

  private suspend fun <T> metadata(dataSource: DbDataSource, block: (DatabaseMetaData, Connection) -> T): T =
    DbConnectionService.getInstance(project).withConnection(dataSource, DbConnectionService.Purpose.METADATA) { c ->
      withContext(Dispatchers.IO) { block(c.metaData, c) }
    }

  private fun escape(md: DatabaseMetaData, name: String): String {
    val esc = runCatching { md.searchStringEscape }.getOrNull().orEmpty()
    if (esc.isEmpty()) return name
    return name.replace(esc, esc + esc).replace("_", esc + "_").replace("%", esc + "%")
  }

  companion object {
    private val SYSTEM_SCHEMAS = setOf("information_schema", "performance_schema", "mysql", "sys", "pg_catalog", "pg_toast")
    private val SIZED_TYPES = setOf("VARCHAR", "CHAR", "CHARACTER", "CHARACTER VARYING", "VARBINARY", "BINARY", "NVARCHAR", "NCHAR")

    fun getInstance(project: Project): DbMetadataCache = project.service()
    fun getInstanceIfCreated(project: Project): DbMetadataCache? = project.serviceIfCreated()

    fun cachedTables(project: Project, dataSourceId: String): List<DbTableRef> = getInstanceIfCreated(project)?.cachedTables(dataSourceId).orEmpty()
    fun cachedColumns(project: Project, table: DbTableRef): List<DbColumnInfo>? = getInstanceIfCreated(project)?.cachedColumns(table)
  }
}
