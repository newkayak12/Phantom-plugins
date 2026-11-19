// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localsharedindex

import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vfs.VirtualFileWithId
import com.intellij.psi.stubs.StubUpdatingIndexStorage
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileBasedIndexInfrastructureExtensionUpdatableIndex
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.FileContentImpl
import com.intellij.util.indexing.IndexInfrastructureExtensionUpdate
import com.intellij.util.indexing.IndexExtension
import com.intellij.util.indexing.IndexedFile
import com.intellij.util.indexing.IndexedHashesSupport
import com.intellij.util.indexing.StorageUpdate
import com.intellij.util.indexing.UpdatableIndex
import com.intellij.util.indexing.ValueContainer
import com.intellij.util.indexing.impl.InputData
import com.intellij.util.indexing.impl.MapReduceIndex
import com.intellij.util.indexing.impl.MapReduceIndexMappingException
import dev.phantom.localsharedindex.store.LocalSharedIndexStore
import dev.phantom.localsharedindex.store.StoreHeader
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Serves the key-value map of a file from the store when another checkout already indexed the same bytes, and
 * records the maps this checkout computes. Everything else, including removals and unsaved document text, goes to
 * [base] untouched.
 */
internal class CachingIndex<K, V, D>(
  private val base: UpdatableIndex<K, V, FileContent, D>,
  private val indexName: String,
  private val codec: EntryCodec<K, V>,
  private val store: LocalSharedIndexStore,
  private val stubs: Boolean,
  private val header: StoreHeader,
) : FileBasedIndexInfrastructureExtensionUpdatableIndex<K, V, FileContent, D>,
    UpdatableIndex<K, V, FileContent, D> by base {

  // Set when a verified hit differed from a fresh computation; the index then runs without the cache until restart.
  private val disabled = AtomicBoolean()

  // Resolved once: a service lookup per file per index is overhead stock indexing does not have.
  private val stats = LocalSharedIndexStatistics.getInstance()

  // A platform clear (rebuild, repaired stub name storage, version change) makes everything cached for this index suspect.
  override fun clear() {
    try {
      base.clear()
    }
    finally {
      store.clear(indexName)
      store.ensureHeader(indexName, header) // the index lives on after a clear and keeps using the cache
    }
  }

  override fun getBaseIndex(): UpdatableIndex<K, V, FileContent, D> = base

  @Suppress("TooGenericExceptionCaught") // decoding a stale payload fails in any way, see the catch below
  override fun mapInputAndPrepareUpdate(inputId: Int, content: FileContent?): IndexInfrastructureExtensionUpdate {
    if (disabled.get() || content !is FileContentImpl || !isCacheable(content, stubs)) {
      return IndexInfrastructureExtensionUpdate(base.mapInputAndPrepareUpdate(inputId, content), false)
    }
    val epoch = store.epochOf(indexName) // before anything is mapped: a clear after this point drops the record
    val key = CacheKeys.keyFor(content, base.extension.indexer)
    val stored = store.get(indexName, key)
    if (stored != null) {
      val data = try {
        codec.decode(stored.payload, inputId)
      }
      // A CRC-valid payload can still be stale for the current indexer, and then the externalizer fails in any way.
      catch (e: Exception) {
        rethrowCancellation(e)
        LOG.debug("Cannot decode the cached entry of $indexName, indexing the file", e)
        store.remove(indexName, key) // unreadable record: forget it and index the file
        null
      }
      if (data != null) {
        val recomputed = verify(content, data)
        if (recomputed == null) {
          stats.hit(content.project, indexName, content.file, stored.mapMicros)
          return IndexInfrastructureExtensionUpdate(prepareFromData(inputId, content, data), true)
        }
        LOG.error("Phantom local shared index: cached data of $indexName differs for ${content.file.path}; cache off for $indexName until restart")
        disabled.set(true)
        store.remove(indexName, key)
        stats.miss(content.project, indexName, content.file)
        return IndexInfrastructureExtensionUpdate(prepareFromData(inputId, content, recomputed), false)
      }
    }
    stats.miss(content.project, indexName, content.file)
    return if (stubs) indexStubs(inputId, content, key, epoch) else indexAndStore(inputId, content, key, epoch)
  }

  /** For every Nth hit ([VERIFY_KEY]) recomputes the map; returns it when it differs from [cached], else null. */
  @Suppress("TooGenericExceptionCaught") // a failing verification must never break indexing
  private fun verify(content: FileContentImpl, cached: Map<K, V>): Map<K, V>? {
    val every = Registry.intValue(VERIFY_KEY, 0)
    if (every <= 0 || verifiedHits.incrementAndGet() % every != 0L) return null
    return try {
      mapLikePlatform(base.extension, content).takeIf { it != cached }
    }
    catch (e: Exception) {
      rethrowCancellation(e)
      LOG.debug("Cannot verify the cached entry of $indexName", e)
      null
    }
  }

  private fun indexAndStore(inputId: Int, content: FileContentImpl, key: ByteArray, epoch: Int): IndexInfrastructureExtensionUpdate {
    val started = System.nanoTime()
    val update = base.mapInputAndPrepareUpdate(inputId, content)
    if (update is MapReduceIndex<*, *, *>.IndexStorageUpdate) {
      @Suppress("UNCHECKED_CAST")
      val data = update.inputData.keyValues as Map<K, V>
      store.put(indexName, key, microsSince(started), epoch) { codec.encode(data) }
    }
    return IndexInfrastructureExtensionUpdate(update, false)
  }

  // The stub storage returns an opaque update, so the map is computed here and the update is built from it.
  private fun indexStubs(inputId: Int, content: FileContentImpl, key: ByteArray, epoch: Int): IndexInfrastructureExtensionUpdate {
    val started = System.nanoTime()
    val data = mapLikePlatform(base.extension, content)
    val micros = microsSince(started)
    store.put(indexName, key, micros, epoch) { codec.encode(data) }
    return IndexInfrastructureExtensionUpdate(prepareFromData(inputId, content, data), false)
  }

  private fun prepareFromData(inputId: Int, content: FileContent, data: Map<K, V>): StorageUpdate {
    val update = base.prepareUpdate(inputId, InputData(data))
    return if (stubs) StubUpdatingIndexStorage.withStubIndexingDiagnosticUpdate(inputId, content, update) else update
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

private fun isCacheable(content: FileContentImpl, stubs: Boolean): Boolean =
  !content.isTransientContent && content.file is VirtualFileWithId &&
    (!stubs || LocalSharedIndexAllowlist.cachesStubsFor(content.fileType, content.fileName))

// A cancelled indexing must not be taken for a bad record: that would drop a good one.
private fun rethrowCancellation(e: Exception) {
  if (e is ProcessCanceledException || e is ControlFlowException || e is CancellationException) throw e
}

private fun microsSince(started: Long): Int = ((System.nanoTime() - started) / 1000).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

// Same contract as MapReduceIndex.mapInputAndPrepareUpdate: a failing indexer is a per-file failure, not an index rebuild.
@Suppress("TooGenericExceptionCaught")
private fun <K, V> mapLikePlatform(extension: IndexExtension<K, V, FileContent>, content: FileContent): Map<K, V> {
  val data = try {
    extension.indexer.map(content)
  }
  catch (e: Exception) {
    if (e is ProcessCanceledException || e is MapReduceIndexMappingException) throw e
    throw MapReduceIndexMappingException(e, extension.javaClass)
  }
  ProgressManager.checkCanceled()
  return data
}

private const val VERIFY_KEY = "phantom.localsharedindex.verify.one.in"

// Counts hits of all indexes, so that "every Nth hit" is store-wide.
private val verifiedHits = AtomicLong()

private val LOG = logger<CachingIndex<*, *, *>>()

internal fun <K, V, D> wrapInCache(
  indexExtension: FileBasedIndexExtension<K, V>,
  baseIndex: UpdatableIndex<K, V, FileContent, D>,
  store: LocalSharedIndexStore,
  layoutId: String?,
): UpdatableIndex<K, V, FileContent, D> {
  val name = indexExtension.name.name
  val header = headerFor(indexExtension, layoutId)
  store.ensureHeader(name, header)
  return CachingIndex(baseIndex, name, EntryCodec.of(indexExtension), store, name == LocalSharedIndexAllowlist.STUBS, header)
}

/**
 * Entries written under another index version, hash version or storage layout are dropped before the first read.
 * Stub bytes carry ids from the serializer name storage, so the stub header also holds a fingerprint. The header also
 * holds the version of this plugin, so a cache written by another release is dropped.
 */
internal fun headerFor(indexExtension: FileBasedIndexExtension<*, *>, layoutId: String?): StoreHeader {
  val fingerprint = if (indexExtension.name.name == LocalSharedIndexAllowlist.STUBS) stubFingerprint() else 0
  // The plugin version is part of the layout: another plugin release may encode or key records differently.
  val layout = "${layoutId ?: ""}|${pluginVersion()}"
  return StoreHeader(LocalSharedIndexStore.FORMAT_VERSION, indexExtension.version, IndexedHashesSupport.getVersion(), layout, fingerprint)
}

private fun pluginVersion(): String = PluginManager.getPluginByClass(LocalSharedIndexExtension::class.java)?.version.orEmpty()
