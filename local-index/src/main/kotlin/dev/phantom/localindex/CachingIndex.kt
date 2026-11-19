// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localindex

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.vfs.VirtualFileWithId
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileBasedIndexInfrastructureExtensionUpdatableIndex
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.FileContentImpl
import com.intellij.util.indexing.IndexInfrastructureExtensionUpdate
import com.intellij.util.indexing.IndexedFile
import com.intellij.util.indexing.IndexedHashesSupport
import com.intellij.util.indexing.UpdatableIndex
import com.intellij.util.indexing.ValueContainer
import com.intellij.util.indexing.impl.InputData
import com.intellij.util.indexing.impl.MapReduceIndex
import dev.phantom.localindex.store.LocalIndexStore
import dev.phantom.localindex.store.StoreHeader

/**
 * Serves the key-value map of a file from the store when another checkout already indexed the same bytes, and
 * records the maps this checkout computes. Everything else, including removals and unsaved document text, goes to
 * [base] untouched.
 */
internal class CachingIndex<K, V, D>(
  private val base: UpdatableIndex<K, V, FileContent, D>,
  private val indexName: String,
  private val codec: EntryCodec<K, V>,
  private val store: LocalIndexStore,
  private val stubs: Boolean,
) : FileBasedIndexInfrastructureExtensionUpdatableIndex<K, V, FileContent, D>,
    UpdatableIndex<K, V, FileContent, D> by base {

  override fun getBaseIndex(): UpdatableIndex<K, V, FileContent, D> = base

  @Suppress("TooGenericExceptionCaught") // decoding a stale payload fails in any way, see the catch below
  override fun mapInputAndPrepareUpdate(inputId: Int, content: FileContent?): IndexInfrastructureExtensionUpdate {
    // TODO(Task 8): bypass when the index is disabled for the session
    if (content !is FileContentImpl || content.isTransientContent || content.file !is VirtualFileWithId) {
      return IndexInfrastructureExtensionUpdate(base.mapInputAndPrepareUpdate(inputId, content), false)
    }
    val stats = LocalIndexStatistics.getInstance()
    val key = CacheKeys.keyFor(content, base.extension.indexer)
    val stored = store.get(indexName, key)
    if (stored != null) {
      val data = try {
        codec.decode(stored.payload, inputId)
      }
      catch (e: ProcessCanceledException) {
        throw e
      }
      // A CRC-valid payload can still be stale for the current indexer, and then the externalizer fails in any way.
      catch (e: Exception) {
        LOG.debug("Cannot decode the cached entry of $indexName, indexing the file", e)
        store.remove(indexName, key) // unreadable record: forget it and index the file
        null
      }
      if (data != null) {
        stats.hit(content.project, indexName, content.file.path, stored.mapMicros)
        return IndexInfrastructureExtensionUpdate(base.prepareUpdate(inputId, InputData(data)), true)
      }
    }
    stats.miss(content.project, indexName, content.file.path)
    val started = System.nanoTime()
    val update = base.mapInputAndPrepareUpdate(inputId, content)
    if (!stubs && update is MapReduceIndex<*, *, *>.IndexStorageUpdate) {
      val micros = ((System.nanoTime() - started) / 1000).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
      @Suppress("UNCHECKED_CAST")
      store.put(indexName, key, micros, codec.encode(update.inputData.keyValues as Map<K, V>))
    }
    return IndexInfrastructureExtensionUpdate(update, false)
  }

  // Java interface defaults: forwarded by hand so that the base index sees them, not the interface's empty body.
  override fun setIndexedStateForFileOnFileIndexMetaData(fileId: Int, fileIndexMetaData: D?, isProvidedByInfrastructureExtension: Boolean) =
    base.setIndexedStateForFileOnFileIndexMetaData(fileId, fileIndexMetaData, isProvidedByInfrastructureExtension)

  override fun setIndexedStateForFile(fileId: Int, file: IndexedFile, isProvidedByInfrastructureExtension: Boolean) =
    base.setIndexedStateForFile(fileId, file, isProvidedByInfrastructureExtension)

  override fun canUpdate(): Boolean = base.canUpdate()

  @Deprecated("Deprecated in the platform; forwarded for completeness")
  override fun getData(key: K & Any): ValueContainer<V> = @Suppress("DEPRECATION") base.getData(key)
}

private val LOG = logger<CachingIndex<*, *, *>>()

internal fun <K, V, D> wrapInCache(
  indexExtension: FileBasedIndexExtension<K, V>,
  baseIndex: UpdatableIndex<K, V, FileContent, D>,
  store: LocalIndexStore,
  layoutId: String?,
): UpdatableIndex<K, V, FileContent, D> {
  val name = indexExtension.name.name
  // Entries written under another index version, hash version or storage layout are dropped before the first read.
  val header = StoreHeader(LocalIndexStore.FORMAT_VERSION, indexExtension.version, IndexedHashesSupport.getVersion(), layoutId ?: "", 0)
  store.ensureHeader(name, header)
  return CachingIndex(baseIndex, name, EntryCodec.of(indexExtension), store, stubs = false)
}
