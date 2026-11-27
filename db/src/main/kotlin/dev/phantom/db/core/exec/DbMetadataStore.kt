// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.io.DigestUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The metadata of one data source as last introspected: names, columns, keys, indexes and routines, never data rows or
 * credentials. It backs [DbMetadataCache] and [DbObjectCache] while they have not loaded a part in this session, so
 * completion and inspections keep working offline and right after a restart.
 */
class DbStoredMetadata(val dataSourceId: String) {
  @Volatile var schemas: List<DbSchemaRef>? = null
  val tables = ConcurrentHashMap<DbSchemaRef, List<DbTableRef>>()
  val columns = ConcurrentHashMap<DbTableRef, List<DbColumnInfo>>()
  val details = ConcurrentHashMap<DbTableRef, List<DbColumnDetail>>()
  val primaryKeys = ConcurrentHashMap<DbTableRef, DbPrimaryKey>()
  val foreignKeys = ConcurrentHashMap<DbTableRef, List<DbForeignKey>>()
  val referencing = ConcurrentHashMap<DbTableRef, List<DbForeignKey>>()
  val indexes = ConcurrentHashMap<DbTableRef, List<DbIndex>>()
  val routines = ConcurrentHashMap<DbSchemaRef, List<DbRoutine>>()
  val triggers = ConcurrentHashMap<DbSchemaRef, List<DbTrigger>>()
  val sequences = ConcurrentHashMap<DbSchemaRef, List<DbSequence>>()

  /** A new schema list replaces the old one; schemas no longer listed lose their tables and objects. */
  @Synchronized
  fun replaceSchemas(list: List<DbSchemaRef>) {
    schemas = list
    for (gone in tables.keys - list.toSet()) replaceTables(gone, emptyList())
    for (map in listOf(tables, routines, triggers, sequences)) map.keys.retainAll(list.toSet())
  }

  /** A new table list replaces the schema's old one; tables no longer listed lose their columns and keys. */
  @Synchronized
  fun replaceTables(schema: DbSchemaRef, list: List<DbTableRef>) {
    val old = tables.put(schema, list).orEmpty()
    for (gone in old - list.toSet()) {
      for (map in listOf(columns, details, primaryKeys, foreignKeys, referencing, indexes)) map.remove(gone)
    }
  }

  val isEmpty: Boolean get() = schemas == null && tables.isEmpty() && columns.isEmpty() && details.isEmpty() && primaryKeys.isEmpty() &&
                               foreignKeys.isEmpty() && referencing.isEmpty() && indexes.isEmpty() && routines.isEmpty() &&
                               triggers.isEmpty() && sequences.isEmpty()
}

/**
 * Keeps [DbStoredMetadata] on disk under the IDE system directory, one file per data source id, so a global data
 * source shares it across projects. A file is read on first use of its data source, never at startup and never on
 * the EDT, and written in the background shortly after a part was introspected again. A file of another
 * [VERSION] or one that cannot be read is deleted.
 */
@Service(Service.Level.APP)
class DbMetadataStore : Disposable {
  private val loaded = ConcurrentHashMap<String, DbStoredMetadata>()
  private val loading = ConcurrentHashMap.newKeySet<String>()
  private val dirty = ConcurrentHashMap.newKeySet<String>()
  private val io = AppExecutorUtil.createBoundedApplicationPoolExecutor("Phantom DB metadata store", 1)

  @Volatile internal var dir: Path = PathManager.getSystemDir().resolve("phantom-db/metadata")
    @TestOnly set

  /** The stored metadata, or null on the EDT while it is still being read (which this call starts). */
  fun get(dataSourceId: String): DbStoredMetadata? {
    loaded[dataSourceId]?.let { return it }
    if (!ApplicationManager.getApplication().isDispatchThread) return load(dataSourceId)
    if (loading.add(dataSourceId)) {
      ApplicationManager.getApplication().executeOnPooledThread {
        try {
          load(dataSourceId)
        }
        finally {
          loading.remove(dataSourceId)
        }
      }
    }
    return null
  }

  /** Changes the stored metadata of [dataSourceId] off the EDT and saves it soon after. */
  suspend fun update(dataSourceId: String, change: (DbStoredMetadata) -> Unit) {
    withContext(Dispatchers.IO) {
      val stored = load(dataSourceId)
      synchronized(stored) { change(stored) }
    }
    if (dirty.add(dataSourceId)) AppExecutorUtil.getAppScheduledExecutorService().schedule({ io.execute { save(dataSourceId) } }, SAVE_DELAY_MS, TimeUnit.MILLISECONDS)
  }

  /** Forgets everything stored for [dataSourceId] and deletes its file. */
  fun drop(dataSourceId: String) {
    loaded[dataSourceId] = DbStoredMetadata(dataSourceId)
    dirty.remove(dataSourceId)
    val path = file(dataSourceId)
    io.execute { Files.deleteIfExists(path) }
  }

  /** Forgets what was read from disk, as a restart does; the next [get] reads the file again. */
  @TestOnly
  fun unload(dataSourceId: String) {
    flush(dataSourceId)
    loaded.remove(dataSourceId)
  }

  /** Writes [dataSourceId]'s pending changes now; for tests and disposal. */
  @TestOnly
  fun flush(dataSourceId: String) {
    io.submit { save(dataSourceId) }.get(10, TimeUnit.SECONDS)
  }

  override fun dispose() {
    for (id in dirty.toList()) save(id)
  }

  private fun load(dataSourceId: String): DbStoredMetadata = loaded[dataSourceId] ?: synchronized(this) {
    loaded[dataSourceId] ?: (read(file(dataSourceId), dataSourceId) ?: DbStoredMetadata(dataSourceId)).also { loaded[dataSourceId] = it }
  }

  private fun save(dataSourceId: String) {
    if (!dirty.remove(dataSourceId)) return
    val stored = loaded[dataSourceId] ?: return
    runCatching { write(file(dataSourceId), stored) }.onFailure { LOG.warn("Cannot save metadata of data source $dataSourceId", it) }
  }

  internal fun file(dataSourceId: String): Path =
    dir.resolve((if (dataSourceId.matches(SAFE_ID)) dataSourceId else DigestUtil.sha256Hex(dataSourceId.toByteArray())) + ".json")

  private class FileForm(val version: Int, val dataSourceId: String, val schemas: List<DbSchemaRef>?, val schemaParts: List<SchemaPart>,
                         val tableParts: List<TablePart>)

  private class SchemaPart(val schema: DbSchemaRef, val tables: List<DbTableRef>?, val routines: List<DbRoutine>?, val triggers: List<DbTrigger>?,
                           val sequences: List<DbSequence>?)

  private class TablePart(val table: DbTableRef, val columns: List<DbColumnInfo>?, val details: List<DbColumnDetail>?, val primaryKey: DbPrimaryKey?,
                          val foreignKeys: List<DbForeignKey>?, val referencing: List<DbForeignKey>?, val indexes: List<DbIndex>?)

  companion object {
    /** Raise when the stored classes change shape; files of another version are deleted instead of read. */
    const val VERSION: Int = 1
    private const val SAVE_DELAY_MS = 2_000L
    private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,100}")
    private val LOG = logger<DbMetadataStore>()
    private val GSON = Gson()

    fun getInstance(): DbMetadataStore = service()

    internal fun write(path: Path, stored: DbStoredMetadata) {
      val form = synchronized(stored) {
        val schemaKeys = stored.tables.keys + stored.routines.keys + stored.triggers.keys + stored.sequences.keys
        val tableKeys = listOf(stored.columns, stored.details, stored.primaryKeys, stored.foreignKeys, stored.referencing, stored.indexes)
          .flatMapTo(LinkedHashSet()) { it.keys }
        FileForm(VERSION, stored.dataSourceId, stored.schemas,
                 schemaKeys.map { SchemaPart(it, stored.tables[it], stored.routines[it], stored.triggers[it], stored.sequences[it]) },
                 tableKeys.map {
                   TablePart(it, stored.columns[it], stored.details[it], stored.primaryKeys[it], stored.foreignKeys[it], stored.referencing[it],
                             stored.indexes[it])
                 })
      }
      Files.createDirectories(path.parent)
      val temp = Files.createTempFile(path.parent, path.fileName.toString(), ".tmp")
      Files.writeString(temp, GSON.toJson(form))
      Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** The metadata in [path], or null when there is none; a file of another version or one that cannot be read is deleted. */
    internal fun read(path: Path, dataSourceId: String): DbStoredMetadata? {
      if (!Files.isRegularFile(path)) return null
      val result = runCatching {
        val json = JsonParser.parseString(Files.readString(path)).asJsonObject
        if (json.get("version")?.asInt != VERSION) return@runCatching null
        val form = GSON.fromJson(json, FileForm::class.java)
        if (form.dataSourceId != dataSourceId) return@runCatching null
        // Gson fills Kotlin non-null fields with null when the file lacks them: check before anyone reads them
        DbStoredMetadata(dataSourceId).apply {
          schemas = form.schemas?.onEach { present(it.dataSourceId) }
          form.schemaParts.forEach { add(it) }
          form.tableParts.forEach { add(it) }
        }
      }
      val stored = result.getOrNull()
      if (stored == null) {
        result.exceptionOrNull()?.let { LOG.info("Dropping unreadable metadata cache $path", it) }
        runCatching { Files.deleteIfExists(path) }
      }
      return stored
    }

    private fun DbStoredMetadata.add(part: SchemaPart) {
      present(part.schema, part.schema.dataSourceId)
      part.tables?.onEach { check(it) }?.let { tables[part.schema] = it }
      part.routines?.onEach { present(it.name, it.schema) }?.let { routines[part.schema] = it }
      part.triggers?.onEach { present(it.name, it.table, it.schema) }?.let { triggers[part.schema] = it }
      part.sequences?.onEach { present(it.name, it.schema) }?.let { sequences[part.schema] = it }
    }

    private fun DbStoredMetadata.add(part: TablePart) {
      val table = check(part.table)
      part.columns?.onEach { present(it.name, it.typeName) }?.let { columns[table] = it }
      part.details?.onEach { present(it.name, it.typeName) }?.let { details[table] = it }
      part.primaryKey?.also { present(it.columns) }?.let { primaryKeys[table] = it }
      part.foreignKeys?.onEach(::check)?.let { foreignKeys[table] = it }
      part.referencing?.onEach(::check)?.let { referencing[table] = it }
      part.indexes?.onEach { present(it.name, it.columns) }?.let { indexes[table] = it }
    }

    private fun check(key: DbForeignKey) {
      check(key.table)
      check(key.refTable)
      present(key.columns, key.refColumns)
    }

    private fun check(table: DbTableRef?): DbTableRef {
      present(table, table?.dataSourceId, table?.name, table?.kind)
      return table!!
    }

    private fun present(vararg values: Any?) = require(values.none { it == null }) { "missing field" }
  }
}
