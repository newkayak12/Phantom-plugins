// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import dev.phantom.db.core.exec.DbColumnDetail
import dev.phantom.db.core.exec.DbColumnInfo
import dev.phantom.db.core.exec.DbConnectionService
import dev.phantom.db.core.exec.DbConsoles
import dev.phantom.db.core.exec.DbIndex
import dev.phantom.db.core.exec.DbMetadataCache
import dev.phantom.db.core.exec.DbObjectCache
import dev.phantom.db.core.exec.DbPrimaryKey
import dev.phantom.db.core.exec.DbSchemaChoice
import dev.phantom.db.core.exec.DbSchemaRef
import dev.phantom.db.core.exec.DbTableRef
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * What is known of a data source: its schema names, and the tables of the schemas loaded so far (always the current
 * one), current schema first.
 */
class SqlSchemaSnapshot(val schemas: List<DbSchemaRef>, val tables: List<DbTableRef>, private val loadedSchemas: Set<DbSchemaRef>) {
  /** The current schema: the one the connection opens in, which the cache lists first. */
  val current: DbSchemaRef? get() = schemas.firstOrNull()

  fun schema(name: String): DbSchemaRef? = schemas.firstOrNull { SqlNames.same(it.name, name) }

  fun isLoaded(schema: DbSchemaRef): Boolean = schema in loadedSchemas

  /** Tables named [name], in [schema] when given, else current schema first. */
  fun find(name: String, schema: String?): List<DbTableRef> = tables.filter {
    SqlNames.same(it.name, name) && (schema == null || SqlNames.same(it.schema ?: it.catalog.orEmpty(), schema))
  }
}

/**
 * The schema behind a SQL file, loaded on demand for completion, navigation and the unresolved-name check, so none
 * of them needs the Database tree to be expanded first. Only what a name needs is read, into the in-memory metadata
 * cache the tree shares: the schema list and the current schema's tables, another schema's tables when a name is
 * qualified with it, a table's columns when a column of it is looked up. Nothing runs at startup or for a project
 * without data sources: this service is created by the first SQL name looked up in a file that has a data source.
 *
 * Loading runs on the data source's metadata connection in the connection service's scope. A caller on a background
 * thread waits up to [WAIT_MS], cancellably; a caller on the EDT never waits and gets what is loaded so far. A load
 * that failed is not retried for [RETRY_MS].
 */
@Service(Service.Level.PROJECT)
class SqlSchema(private val project: Project) {
  private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
  private val failedAt = ConcurrentHashMap<String, Long>()

  /**
   * The data source's tables, or null when they are not loaded (still loading, timed out, or failed). [current]: the
   * schema chosen for the file, which then comes first; null or a name the data source does not list means its default.
   */
  fun snapshot(dataSource: DbDataSource, load: Boolean = true, current: DbSchemaChoice? = null): SqlSchemaSnapshot? {
    loaded(dataSource, current)?.let { return it }
    if (!load) return null
    val job = start("tables:" + dataSource.id + ":" + current?.name.orEmpty() + ":" + current?.catalog.orEmpty()) { load(dataSource, current) } ?: return null
    await(job)
    return loaded(dataSource, current)
  }

  /** [snapshot] with the schema chosen for [file] as the current one. */
  fun snapshotFor(file: PsiFile, dataSource: DbDataSource, load: Boolean = true): SqlSchemaSnapshot? =
    snapshot(dataSource, load, schemaFor(file))

  /** The snapshot with [schema]'s tables loaded too, or null when they are not loaded. */
  fun withSchema(dataSource: DbDataSource, schema: DbSchemaRef, load: Boolean = true, current: DbSchemaChoice? = null): SqlSchemaSnapshot? {
    loaded(dataSource, current)?.takeIf { it.isLoaded(schema) }?.let { return it }
    if (!load) return null
    val job = start("schema:" + schema) { DbMetadataCache.getInstance(project).tables(dataSource, schema) } ?: return null
    await(job)
    return loaded(dataSource, current)?.takeIf { it.isLoaded(schema) }
  }

  /** The columns of [table], or null when they are not loaded. */
  fun columns(dataSource: DbDataSource, table: DbTableRef, load: Boolean = true): List<DbColumnInfo>? {
    DbMetadataCache.cachedColumns(project, table)?.let { return it }
    if (!load) return null
    val job = start("columns:" + table) { DbMetadataCache.getInstance(project).columns(dataSource, table) } ?: return null
    await(job)
    return DbMetadataCache.cachedColumns(project, table)
  }

  /** Nullability, defaults and auto-increment of [table]'s columns, or null when they are not loaded. */
  fun columnDetails(dataSource: DbDataSource, table: DbTableRef, load: Boolean = true): List<DbColumnDetail>? {
    DbObjectCache.getInstanceIfCreated(project)?.cachedColumnDetails(table)?.let { return it }
    if (!load) return null
    val job = start("details:" + table) { DbObjectCache.getInstance(project).columnDetails(dataSource, table) } ?: return null
    await(job)
    return DbObjectCache.getInstanceIfCreated(project)?.cachedColumnDetails(table)
  }

  /** The primary key and the other indexes of [table], or null when they are not loaded. */
  fun indexes(dataSource: DbDataSource, table: DbTableRef, load: Boolean = true): Pair<DbPrimaryKey, List<DbIndex>>? {
    fun cached(): Pair<DbPrimaryKey, List<DbIndex>>? {
      val cache = DbObjectCache.getInstanceIfCreated(project) ?: return null
      val indexes = cache.cachedIndexes(table) ?: return null
      return (cache.cachedPrimaryKey(table) ?: return null) to indexes
    }
    cached()?.let { return it }
    if (!load) return null
    val job = start("indexes:" + table) { DbObjectCache.getInstance(project).indexes(dataSource, table) } ?: return null
    await(job)
    return cached()
  }

  /** Loads the schema list and the [current] (else default) schema's tables now; for tests and callers already in a coroutine. */
  suspend fun load(dataSource: DbDataSource, current: DbSchemaChoice? = null): SqlSchemaSnapshot? {
    val cache = DbMetadataCache.getInstance(project)
    pick(cache.schemas(dataSource), current)?.let { cache.tables(dataSource, it) }
    return loaded(dataSource, current)
  }

  private fun loaded(dataSource: DbDataSource, chosen: DbSchemaChoice?): SqlSchemaSnapshot? {
    val cache = DbMetadataCache.getInstanceIfCreated(project) ?: return null
    val listed = cache.cachedSchemas(dataSource.id) ?: return null
    val current = pick(listed, chosen)
    val schemas = listOfNotNull(current) + listed.filter { it != current }
    if (current != null && !cache.hasCachedTables(current)) return null
    val loaded = schemas.filter(cache::hasCachedTables).toSet()
    val order = schemas.withIndex().associate { it.value to it.index }
    val tables = cache.cachedTables(dataSource.id).sortedBy { order[DbSchemaRef(it.dataSourceId, it.catalog, it.schema)] ?: Int.MAX_VALUE }
    return SqlSchemaSnapshot(schemas, tables, loaded)
  }

  private fun start(key: String, block: suspend () -> Unit): CompletableDeferred<Unit>? {
    failedAt[key]?.let { if (System.currentTimeMillis() - it < RETRY_MS) return null else failedAt.remove(key) }
    inFlight[key]?.let { return it }
    val job = CompletableDeferred<Unit>()
    if (inFlight.putIfAbsent(key, job) != null) return inFlight[key]
    DbConnectionService.getInstance(project).launch {
      try {
        block()
        job.complete(Unit)
        // names checked before the load finished were left alone: look again
        if (!project.isDisposed) DaemonCodeAnalyzer.getInstance(project).restart("Phantom SQL schema loaded")
      }
      catch (e: Throwable) {
        failedAt[key] = System.currentTimeMillis()
        job.completeExceptionally(e)
        if (e is kotlinx.coroutines.CancellationException) throw e
      }
      finally {
        inFlight.remove(key, job)
      }
    }
    return job
  }

  private fun await(job: CompletableDeferred<Unit>) {
    if (ApplicationManager.getApplication().isDispatchThread) return
    val deadline = System.currentTimeMillis() + WAIT_MS
    while (!job.isCompleted && System.currentTimeMillis() < deadline) {
      ProgressManager.checkCanceled()
      Thread.sleep(10)
    }
  }

  companion object {
    /** The chosen schema if the data source lists it, else the default one, which the cache lists first. */
    private fun pick(schemas: List<DbSchemaRef>, chosen: DbSchemaChoice?): DbSchemaRef? {
      val named = chosen?.takeUnless { it.isDefault }?.let { c -> schemas.filter { SqlNames.same(it.name, c.name) } }.orEmpty()
      return named.firstOrNull { chosen?.catalog != null && it.catalog == chosen.catalog } ?: named.firstOrNull() ?: schemas.firstOrNull()
    }

    /** The schema chosen for the file SQL is in (see [DbConsoles.schemaFor]); null for injected SQL and the default. */
    fun schemaFor(file: PsiFile): DbSchemaChoice? {
      if (InjectedLanguageManager.getInstance(file.project).isInjectedFragment(file)) return null
      return DbConsoles.schemaFor(file.project, file.virtualFile ?: file.viewProvider.virtualFile)
             ?: file.originalFile.takeIf { it != file }?.let { DbConsoles.schemaFor(file.project, it.virtualFile) }
    }

    const val WAIT_MS: Long = 2_000
    const val RETRY_MS: Long = 30_000

    fun getInstance(project: Project): SqlSchema = project.service()

    /**
     * The data source a SQL file runs on: the console's, or the one chosen for a `.sql` file. SQL injected into a Java
     * or Kotlin string has none of its own: then the project's only data source, or, when [strict] is false, the first
     * whose schema is loaded.
     */
    fun dataSourceFor(file: PsiFile, strict: Boolean): DbDataSource? {
      val project = file.project
      val injection = InjectedLanguageManager.getInstance(project)
      if (!injection.isInjectedFragment(file)) {
        return DbConsoles.dataSourceFor(project, file.virtualFile ?: file.viewProvider.virtualFile)
               ?: file.originalFile.takeIf { it != file }?.let { DbConsoles.dataSourceFor(project, it.virtualFile) }
      }
      val all = DbDataSourceService.getInstance(project).dataSources
      if (all.size == 1) return all[0]
      if (strict) return null
      return all.firstOrNull { DbMetadataCache.cachedTables(project, it.id).isNotEmpty() }
    }
  }
}
