// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localsharedindex

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.project.Project
import com.intellij.psi.stubs.StubIndexExtension
import com.intellij.psi.stubs.StubIndexKey
import com.intellij.psi.stubs.StubUpdatingIndex
import com.intellij.openapi.vfs.newvfs.ManagingFS
import com.intellij.openapi.vfs.newvfs.persistent.PersistentFS
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileBasedIndexImpl
import com.intellij.util.indexing.FileBasedIndexInfrastructureExtension
import com.intellij.util.indexing.FileBasedIndexInfrastructureExtension.FileIndexingStatusProcessor
import com.intellij.util.indexing.FileBasedIndexInfrastructureExtension.InitializationResult
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.indexing.IndexInfrastructure
import com.intellij.util.indexing.UpdatableIndex
import dev.phantom.localsharedindex.store.LocalSharedIndexStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/**
 * Wraps the content-only map-reduce indexes with a [CachingIndex] backed by an on-disk store under the index root.
 * Without a store (another IDE process holds its lock) every index passes through untouched.
 */
class LocalSharedIndexExtension : FileBasedIndexInfrastructureExtension {
  @Volatile
  var store: LocalSharedIndexStore? = null
    private set

  @Volatile
  private var layoutId: String? = null

  override fun createFileIndexingStatusProcessor(project: Project): FileIndexingStatusProcessor? =
    if (store != null) LocalSharedIndexStatusProcessor(project) else null

  override fun <K, V> combineIndex(
    indexExtension: FileBasedIndexExtension<K, V>,
    baseIndex: UpdatableIndex<K, V, FileContent, *>,
  ): UpdatableIndex<K, V, FileContent, *>? {
    val store = store ?: return null
    // StubIndexImpl.registerIndexer passes each stub index here; it runs after initialize (StubUpdatingIndex creates the
    // stub indexes while FileBasedIndex registers its indexes), so the store is open by then.
    if (indexExtension.name is StubIndexKey<*, *>) {
      return if (LocalSharedIndexAllowlist.aliasable(LocalSharedIndexAllowlist.STUBS)) StubAliasIndex(baseIndex, AliasRegistry.of(LocalSharedIndexAllowlist.STUBS)) else null
    }
    if (!LocalSharedIndexAllowlist.accepts(indexExtension)) return null
    return wrapInCache(indexExtension, baseIndex, store, layoutId)
  }

  // The index's version changed: its cached entries were written by the old indexer.
  // Its data is dropped and every file indexed again (all indexing stamps are outdated), so aliases restored from the
  // previous session would point at donors that have no data yet.
  override fun onFileBasedIndexVersionChanged(indexId: ID<*, *>) {
    store?.clear(indexId.name)
    AliasRegistry.clear(indexId.name)
  }

  // A stub index changed its version: every cached stub tree carries its old indexed-stub bytes.
  override fun onStubIndexVersionChanged(indexId: StubIndexKey<*, *>) {
    val store = store ?: return
    store.clear(LocalSharedIndexAllowlist.STUBS)
    AliasRegistry.clear(LocalSharedIndexAllowlist.STUBS)
    // Without a header Stubs would stay uncached for the rest of the session.
    FileBasedIndexExtension.EXTENSION_POINT_NAME.extensionList.firstOrNull { it.name.name == LocalSharedIndexAllowlist.STUBS }
      ?.let { store.ensureHeader(LocalSharedIndexAllowlist.STUBS, headerFor(it, layoutId)) }
  }

  override fun initialize(indexLayoutId: String?): InitializationResult {
    layoutId = indexLayoutId
    store?.close()
    val maxBytes = AdvancedSettings.getInt(MAX_SIZE_SETTING).coerceAtLeast(1).toLong() shl 20
    store = LocalSharedIndexStore.open(PathManager.getIndexRoot().resolve(ROOT), maxBytes)
    if (store != null) restoreAliases()
    if (store == null) LOG.warn("Phantom Local Shared Index: the cache at ${PathManager.getIndexRoot().resolve(ROOT)} is locked by another IDE process or unreadable; indexes are not cached in this process")
    return InitializationResult.SUCCESSFULLY
  }

  override fun attachData(project: Project) = Unit

  override fun getPersistentStateRoot(): String = ROOT

  override fun resetPersistentState() {
    store?.clearAll()
    AliasRegistry.clearAll()
  }

  // The platform calls this after it dropped the data of a broken index (FileBasedIndexImpl.initIndexStorage).
  override fun resetPersistentState(indexId: ID<*, *>) {
    store?.clear(indexId.name)
    AliasRegistry.clear(indexId.name)
  }

  override fun shutdown() {
    if (store != null) AliasRegistry.save(donorsFile(), vfsStamp())
    store?.close()
    store = null
  }

  private fun donorsFile() = PathManager.getIndexRoot().resolve(ROOT).resolve("donors.bin")

  /** Writes the donor list now, off the calling thread (indexing listeners run on the indexing thread). */
  fun saveDonors() {
    if (store == null) return
    ApplicationManager.getApplication().executeOnPooledThread { if (store != null) AliasRegistry.save(donorsFile(), vfsStamp()) }
  }

  // Ids of the saved list are only meaningful in the VFS they come from; a rebuilt VFS also drops all indexes.
  private fun vfsStamp(): Long = ManagingFS.getInstance().creationTimestamp

  private fun isLiveFile(fileId: Int): Boolean = PersistentFS.getInstance().findFileById(fileId)?.isValid == true

  /**
   * Restores the aliases saved by the last session. Files whose alias was not restored are still marked indexed and
   * would answer nothing: they are indexed again. When aliases may be missing altogether, which files that concerns is
   * unknown, so the aliased indexes are rebuilt.
   */
  internal fun restoreAliases(
    file: Path = donorsFile(),
    vfsStamp: Long = vfsStamp(),
    donorExists: (Int) -> Boolean = ::isLiveFile,
  ): CompletableFuture<*> {
    val started = System.nanoTime()
    return AliasRegistry.loadInBackground { AliasRegistry.load(file, vfsStamp) }.thenAccept { restored ->
      if (!restored.trusted) {
        rebuildAliasedIndexes()
        return@thenAccept
      }
      val read = System.nanoTime()
      // Until here an alias of a donor deleted in an earlier session answers that file id's data, which the platform
      // removed with the file: nothing. Its file is indexed right after.
      val orphans = restored.orphans + AliasRegistry.dropGoneDonors(donorExists)
      reindexLater(orphans)
      LOG.info("Phantom local shared index: donor list read in ${(read - started) / 1_000_000} ms, donors checked in " +
               "${(System.nanoTime() - read) / 1_000_000} ms, ${orphans.size} files to index again")
    }
  }

  private fun reindexLater(fileIds: Set<Int>) {
    if (fileIds.isEmpty()) return
    afterInitialization {
      val fs = PersistentFS.getInstance()
      for (id in fileIds) fs.findFileById(id)?.let { FileBasedIndex.getInstance().requestReindex(it) }
    }
  }

  /**
   * FileBasedIndex.requestRebuild clears the index before its next use and advances its version
   * (FileBasedIndexImpl.clearIndex), so every file is indexed again; for Stubs it is the platform's own rebuild path
   * (StubIndexImpl.forceRebuild), which also clears every stub index (StubUpdatingIndexStorage.doClear). The cached
   * maps stay: they do not depend on the aliases.
   */
  private fun rebuildAliasedIndexes() {
    LOG.info("Phantom local shared index: the last session ended without saving its aliases, rebuilding ${LocalSharedIndexAllowlist.ALIASED.sorted()}")
    afterInitialization {
      for (name in LocalSharedIndexAllowlist.ALIASED) {
        val id = ID.findByName<Any, Any>(name) ?: continue
        rebuildsKeepingCache += name
        FileBasedIndex.getInstance().requestRebuild(id)
      }
    }
  }

  // FileBasedIndex calls need an initialized index; this runs inside its initialization.
  private fun afterInitialization(action: () -> Unit) {
    ApplicationManager.getApplication().executeOnPooledThread {
      (FileBasedIndex.getInstance() as FileBasedIndexImpl).waitUntilIndicesAreInitialized()
      action()
    }
  }

  // -1 so that this extension never changes the version of the indexes it does not wrap
  override fun getVersion(): Int = -1

  companion object {
    const val ROOT = "phantom-local-shared-index"
    const val MAX_SIZE_SETTING = "phantom.localsharedindex.max.size.mb"
    private val LOG = logger<LocalSharedIndexExtension>()

    fun getInstance(): LocalSharedIndexExtension = FileBasedIndexInfrastructureExtension.EP_NAME.findExtensionOrFail(LocalSharedIndexExtension::class.java)
  }
}

/**
 * Identifies what the ids inside cached stub bytes mean: the registered stub indexes with their versions, and the
 * bytes of the platform's own Stubs version file. The serializer name storage (`rep.names`) can be recreated, and its
 * ids renumbered, in a session where this plugin was off or its store did not open, which no hook here would see. Every
 * Stubs rebuild path (version change, corruption retry, dropped index root) rewrites that version file, so a changed
 * file changes the header, and the store empties the Stubs entries when the header is ensured.
 */
internal fun stubFingerprint(): Long {
  val indexes = StubIndexExtension.EP_NAME.extensionList.map { "${it.key.name}:${it.version}" }.sorted()
  val versionFile = runCatching { Files.readAllBytes(IndexInfrastructure.getVersionFile(StubUpdatingIndex.INDEX_ID)) }.getOrNull()
  return indexes.joinToString("\n").hashCode().toLong() * 31 + (versionFile?.contentHashCode() ?: 0)
}
