// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.db

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import dev.phantom.ai.core.AiState
import dev.phantom.ai.mcp.AgentTool
import dev.phantom.ai.mcp.AgentTools
import dev.phantom.ai.mcp.McpToolError
import dev.phantom.db.core.DbAuthKind
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.DbDataSourceService
import dev.phantom.db.core.DbPasswordStorage
import dev.phantom.db.core.exec.DbConnectionService
import dev.phantom.db.core.exec.DbConsoles
import dev.phantom.db.core.exec.DbMetadataCache
import dev.phantom.db.core.exec.DbSchemaRef
import dev.phantom.db.core.exec.SqlSafety
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The data sources of Phantom Database Console for the agents, read-only: which there are, their tables and columns,
 * and SELECT-like queries. Connections are the console's own (its saved credentials, SSH tunnels and drivers); the
 * agent never sees a password. A query runs in a read-only transaction that is rolled back, whatever it was.
 * Changes are only drafted: [DRAFT] writes the statements into the data source's console for the user to read and run.
 * Off until the user turns on the database tools in the settings: what these tools return goes into the agent's
 * conversation, and so to the agent's AI provider.
 */
class DbAgentTools(
  private val enabled: () -> Boolean = { AiState.getInstance().state.dbTools },
  /** Asks the user whether agents may read [DbDataSource]; a seam for tests. */
  private val ask: (Project, DbDataSource) -> Boolean = ::askUser,
) : AgentTools {
  override fun tools(): List<AgentTool> = if (!enabled()) emptyList() else listOf(
    AgentTool(LIST, LIST_DESCRIPTION, JsonObject(), emptyList(), readOnly = true) { worktree, _ -> list(projectOf(worktree)) },
    AgentTool(TABLES, TABLES_DESCRIPTION, props("database" to DATABASE, "schema" to SCHEMA), listOf("database"), readOnly = true) { worktree, args ->
      val project = projectOf(worktree)
      tables(project, dataSource(project, args), string(args, "schema"))
    },
    AgentTool(DESCRIBE, DESCRIBE_DESCRIPTION, props("database" to DATABASE, "table" to "The table or view name.", "schema" to SCHEMA),
      listOf("database", "table"), readOnly = true) { worktree, args ->
      val project = projectOf(worktree)
      describe(project, dataSource(project, args), string(args, "table") ?: throw McpToolError("table is required"), string(args, "schema"))
    },
    AgentTool(QUERY, QUERY_DESCRIPTION, props("database" to DATABASE, "sql" to "One read-only statement: SELECT, WITH, SHOW, EXPLAIN, DESCRIBE.",
      "schema" to SCHEMA, "max_rows" to "Rows to return (default $DEFAULT_ROWS, at most $MAX_ROWS)."), listOf("database", "sql"), readOnly = true) { worktree, args ->
      val project = projectOf(worktree)
      val rows = args.get("max_rows")?.takeIf { it.isJsonPrimitive }?.asString?.toDoubleOrNull()?.toInt() ?: DEFAULT_ROWS
      query(project, dataSource(project, args), string(args, "sql") ?: throw McpToolError("sql is required"), string(args, "schema"), rows.coerceIn(1, MAX_ROWS))
    },
    AgentTool(DRAFT, DRAFT_DESCRIPTION, props("database" to DATABASE, "sql" to "The statements to draft, ending with ';'; any kind (INSERT, UPDATE, DELETE, DDL)."),
      listOf("database", "sql"), readOnly = false) { worktree, args ->
      val project = projectOf(worktree)
      draft(project, dataSource(project, args), string(args, "sql") ?: throw McpToolError("sql is required"))
    },
  )

  private fun list(project: Project): String {
    val sources = DbDataSourceService.getInstance(project).dataSources
    if (sources.isEmpty()) return "No data sources in Phantom's Database Console for ${project.name}."
    return sources.joinToString("\n") { ds ->
      listOfNotNull(ds.name, ds.dbms.name, if (ds.readOnly) "read-only" else null, ds.schemas.takeIf { it.isNotBlank() }?.let { "schemas: $it" })
        .joinToString("  ")
    }
  }

  private fun tables(project: Project, ds: DbDataSource, schema: String?): String = connected(project, ds) {
    val cache = DbMetadataCache.getInstance(project)
    val schemas = cache.schemas(ds)
    val chosen = if (schema == null) schemas.firstOrNull() else schemaNamed(schemas, schema)
      ?: return@connected "No schema $schema in ${ds.name}; its schemas: ${schemas.joinToString { it.name }}"
    if (chosen == null) return@connected "${ds.name} has no schemas."
    val tables = cache.tables(ds, chosen)
    val head = "${ds.name}.${chosen.name}: ${tables.size} tables and views" +
      (if (schema == null && schemas.size > 1) " (other schemas: ${schemas.drop(1).joinToString { it.name }})" else "")
    (listOf(head) + tables.map { if (it.isView) "${it.name}  (view)" else it.name }).joinToString("\n")
  }

  private fun describe(project: Project, ds: DbDataSource, table: String, schema: String?): String = connected(project, ds) {
    val cache = DbMetadataCache.getInstance(project)
    val schemas = cache.schemas(ds)
    val inSchemas = if (schema == null) schemas else listOfNotNull(schemaNamed(schemas, schema) ?: throw McpToolError("No schema $schema in ${ds.name}"))
    val found = inSchemas.firstNotNullOfOrNull { s -> cache.tables(ds, s).firstOrNull { it.name.equals(table, ignoreCase = true) } }
      ?: throw McpToolError("No table $table in ${ds.name}${schema?.let { ".$it" } ?: ""}; list_tables shows them")
    val columns = cache.columns(ds, found)
    (listOf("${found.schema ?: found.catalog ?: ds.name}.${found.name}${if (found.isView) " (view)" else ""}") + columns.map { c ->
      listOfNotNull(c.name, c.typeName, if (c.nullable) null else "NOT NULL", if (c.primaryKey) "PK" else null).joinToString("  ")
    }).joinToString("\n")
  }

  private fun query(project: Project, ds: DbDataSource, sql: String, schema: String?, maxRows: Int): String {
    val statement = sql.trim().removeSuffix(";").trim()
    val keyword = SqlSafety.firstKeyword(statement)
    if (keyword !in READS) throw McpToolError("Only read-only statements run here (SELECT, WITH, SHOW, EXPLAIN, DESCRIBE); this one starts with ${keyword ?: "nothing"}. Use $DRAFT to write changes into the console for the user")
    if (statement.contains(';')) throw McpToolError("One statement at a time")
    return connected(project, ds) {
      val schemaRef = schema?.let { name -> schemaNamed(DbMetadataCache.getInstance(project).schemas(ds), name) ?: throw McpToolError("No schema $name in ${ds.name}") }
      DbConnectionService.getInstance(project).withConnection(ds, DbConnectionService.Purpose.METADATA) { connection ->
        readOnly(connection) {
          schemaRef?.let { use(connection, it) }
          connection.createStatement().use { st ->
            st.maxRows = maxRows + 1
            st.queryTimeout = QUERY_TIMEOUT_S
            if (!st.execute(statement)) return@readOnly "No rows (the statement returned none)."
            st.resultSet.use { rs ->
              val md = rs.metaData
              val names = (1..md.columnCount).map { md.getColumnLabel(it) }
              val rows = ArrayList<List<String>>()
              while (rows.size <= maxRows && rs.next()) rows += (1..md.columnCount).map { cell(rs.getObject(it)) }
              DbRows.table(names, rows.take(maxRows), more = rows.size > maxRows)
            }
          }
        }
      }
    }
  }

  /** Appends [sql] to the data source's console in an editor, without running it. */
  private fun draft(project: Project, ds: DbDataSource, sql: String): String {
    val text = sql.trim()
    val started = AtomicBoolean()
    val done = CompletableFuture<String?>()
    ApplicationManager.getApplication().invokeLater({
      if (!started.compareAndSet(false, true)) return@invokeLater
      try {
        done.complete(DbConsoles.openConsole(project, ds, text)?.name)
      }
      catch (e: Throwable) {
        done.completeExceptionally(e)
      }
    }, ModalityState.nonModal(), project.disposed)
    val console = try {
      try {
        done.get(EDT_WAIT_S, TimeUnit.SECONDS)
      }
      catch (e: TimeoutException) {
        if (started.compareAndSet(false, true)) throw McpToolError("Phantom is busy (a dialog is open); nothing was drafted, try again")
        done.get()
      }
    }
    catch (e: ExecutionException) {
      throw e.cause ?: e
    }
    console ?: throw McpToolError("Cannot open a console for ${ds.name}")
    return "Drafted in the console $console of ${ds.name}, not run: the user reviews and runs it there" +
      (if (ds.readOnly) " (${ds.name} is read-only in Phantom, so the console will refuse changes until that is turned off)." else ".")
  }

  /** Runs [block] in a read-only transaction that is rolled back, then puts the connection back as it was. */
  private fun <T> readOnly(connection: Connection, block: () -> T): T {
    val autoCommit = connection.autoCommit
    val readOnly = connection.isReadOnly
    val catalog = runCatching { connection.catalog }.getOrNull()
    val schema = runCatching { connection.schema }.getOrNull()
    try {
      runCatching { connection.isReadOnly = true }
      connection.autoCommit = false
      return block()
    }
    finally {
      runCatching { connection.rollback() }
      runCatching { connection.autoCommit = autoCommit }
      runCatching { connection.isReadOnly = readOnly }
      runCatching { if (catalog != null && connection.catalog != catalog) connection.catalog = catalog }
      runCatching { if (schema != null && connection.schema != schema) connection.schema = schema }
    }
  }

  private fun use(connection: Connection, schema: DbSchemaRef) {
    if (schema.schema != null) connection.schema = schema.schema else connection.catalog = schema.catalog
  }

  /** Runs [block] against [ds] with the console's connection, answering a failure as a tool error. */
  private fun connected(project: Project, ds: DbDataSource, block: suspend () -> String): String {
    val needsPassword = ds.authKind == DbAuthKind.PASSWORD && ds.credentialStorage == DbPasswordStorage.NEVER &&
      DbDataSourceService.getInstance(project).password(ds.id).isNullOrEmpty() && !DbConnectionService.getInstance(project).isConnected(ds.id)
    if (needsPassword) throw McpToolError("${ds.name} asks for its password each time; connect to it in Phantom's Database Console first")
    return try {
      runBlocking { withTimeout(TIMEOUT_MS) { block() } }
    }
    catch (e: TimeoutCancellationException) {
      throw McpToolError("${ds.name} did not answer in ${TIMEOUT_MS / 1000} s")
    }
    catch (e: SQLException) {
      throw McpToolError("${ds.name}: ${e.message ?: e.javaClass.simpleName}")
    }
  }

  private fun dataSource(project: Project, args: JsonObject): DbDataSource {
    val name = string(args, "database") ?: throw McpToolError("database is required")
    val sources = DbDataSourceService.getInstance(project).dataSources
    val ds = sources.firstOrNull { it.name == name } ?: sources.firstOrNull { it.name.equals(name, ignoreCase = true) }
      ?: throw McpToolError("No data source $name; list_databases shows them: ${sources.joinToString { it.name }}")
    allow(project, ds)
    return ds
  }

  /**
   * The setting lets agents use the Database Console at all; each data source is still asked for once, naming it and
   * where its results go, before anything of it (tables, columns, rows) reaches the agent and its provider.
   */
  private fun allow(project: Project, ds: DbDataSource) {
    val state = AiState.getInstance().state
    if (ds.id in state.dbAllowedSources) return
    if (!ask(project, ds)) throw McpToolError("The user did not allow agents to read the data source ${ds.name}. Do not retry; ask the user if it is needed.")
    state.dbAllowedSources = (state.dbAllowedSources + ds.id).toMutableList()
  }

  private fun schemaNamed(schemas: List<DbSchemaRef>, name: String): DbSchemaRef? =
    schemas.firstOrNull { it.name == name } ?: schemas.firstOrNull { it.name.equals(name, ignoreCase = true) }

  /** The open project holding the agent's worktree, else the only open project. */
  private fun projectOf(worktree: String?): Project {
    val open = ProjectManager.getInstance().openProjects.filter { !it.isDisposed }
    val root = worktree?.let { Path.of(it).normalize() }
    return open.filter { p -> root != null && p.basePath?.let { root.startsWith(Path.of(it)) } == true }.maxByOrNull { it.basePath!!.length }
      ?: open.singleOrNull()
      ?: throw McpToolError("This workspace is not open in Phantom")
  }

  private fun cell(value: Any?): String = when (value) {
    null -> "NULL"
    is ByteArray -> "<${value.size} bytes>"
    is java.sql.Clob -> runCatching { value.getSubString(1, minOf(value.length(), MAX_CELL.toLong()).toInt()) }.getOrDefault("<clob>")
    is java.sql.Blob -> "<blob>"
    else -> value.toString()
  }.let { if (it.length > MAX_CELL) it.take(MAX_CELL) + "…" else it }

  private fun string(args: JsonObject, key: String): String? = args.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

  private fun props(vararg entries: Pair<String, String>) = JsonObject().apply {
    for ((key, description) in entries) add(key, JsonObject().apply {
      addProperty("type", if (key == "max_rows") "integer" else "string")
      addProperty("description", description)
    })
  }

  companion object {
    const val LIST = "list_databases"
    const val TABLES = "list_tables"
    const val DESCRIBE = "describe_table"
    const val QUERY = "query_database"
    const val DRAFT = "draft_sql"
    private const val EDT_WAIT_S = 30L
    private val READS = setOf("SELECT", "WITH", "SHOW", "EXPLAIN", "DESCRIBE", "DESC", "VALUES", "TABLE")
    private const val DEFAULT_ROWS = 50
    private const val MAX_ROWS = 500
    private const val MAX_CELL = 200
    private const val QUERY_TIMEOUT_S = 30
    private const val TIMEOUT_MS = 60_000L
    private const val DATABASE = "The data source's name, as list_databases shows it."
    private const val SCHEMA = "The schema (database, in MySQL); the data source's default when left out."
    private const val LIST_DESCRIPTION =
      "The data sources of Phantom's Database Console (the user's saved connections): name, DBMS, read-only. " +
        "Use them instead of asking for connection details; passwords stay in Phantom."
    private const val TABLES_DESCRIPTION = "The tables and views of a schema of a data source, and its other schemas."
    private const val DESCRIBE_DESCRIPTION = "The columns of a table: name, type, NOT NULL, PK. Cheaper than querying the catalog."
    private const val QUERY_DESCRIPTION =
      "Runs one read-only statement (SELECT, WITH, SHOW, EXPLAIN, DESCRIBE) on a data source and returns the rows as a " +
        "tab-separated table. It runs in a read-only transaction that is rolled back; nothing can be changed. Ask for few rows."
    private const val DRAFT_DESCRIPTION =
      "Writes SQL into the data source's console in Phantom's editor, appended at the end, and does not run it: the user " +
        "reviews and runs it. The way to propose changes (INSERT, UPDATE, DELETE, DDL); nothing reaches the database from here."
  }
}

/** Rows as a compact tab-separated table for an agent. */
internal object DbRows {
  fun table(names: List<String>, rows: List<List<String>>, more: Boolean): String {
    val lines = listOf(names.joinToString("\t")) + rows.map { row -> row.joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') } }
    val tail = if (more) "... more rows; raise max_rows or narrow the query" else "(${rows.size} rows)"
    return (lines + tail).joinToString("\n")
  }
}

/** On the EDT, over whatever dialog is open: may agents read [ds]? Its results go to the agent's AI provider. */
private fun askUser(project: Project, ds: DbDataSource): Boolean {
  var answer = false
  ApplicationManager.getApplication().invokeAndWait({
    answer = com.intellij.openapi.ui.Messages.showYesNoDialog(project,
      dev.phantom.ai.core.PhantomAiBundle.message("db.consent.message", ds.name, ds.dbms.name, ds.user.ifBlank { "-" }),
      dev.phantom.ai.core.PhantomAiBundle.message("db.consent.title"),
      dev.phantom.ai.core.PhantomAiBundle.message("db.consent.allow"), dev.phantom.ai.core.PhantomAiBundle.message("db.consent.deny"),
      com.intellij.openapi.ui.Messages.getWarningIcon()) == com.intellij.openapi.ui.Messages.YES
  }, ModalityState.any())
  return answer
}
