// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localindex

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.intellij.psi.stubs.StubIndexExtension
import com.intellij.psi.stubs.StubIndexKey
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileBasedIndexInfrastructureExtension
import com.intellij.util.indexing.FileBasedIndexInfrastructureExtension.FileIndexingStatusProcessor
import com.intellij.util.indexing.FileBasedIndexInfrastructureExtension.InitializationResult
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.indexing.UpdatableIndex
import dev.phantom.localindex.store.LocalIndexStore

/**
 * Wraps the content-only map-reduce indexes with a [CachingIndex] backed by an on-disk store under the index root.
 * Without a store (another IDE process holds its lock) every index passes through untouched.
 */
class LocalIndexExtension : FileBasedIndexInfrastructureExtension {
  @Volatile
  var store: LocalIndexStore? = null
    private set

  @Volatile
  private var layoutId: String? = null

  override fun createFileIndexingStatusProcessor(project: Project): FileIndexingStatusProcessor? = null

  override fun <K, V> combineIndex(
    indexExtension: FileBasedIndexExtension<K, V>,
    baseIndex: UpdatableIndex<K, V, FileContent, *>,
  ): UpdatableIndex<K, V, FileContent, *>? {
    val store = store ?: return null
    if (!LocalIndexAllowlist.accepts(indexExtension)) return null
    return wrapInCache(indexExtension, baseIndex, store, layoutId)
  }

  override fun onFileBasedIndexVersionChanged(indexId: ID<*, *>) = Unit

  // A stub index changed its version: every cached stub tree carries its old indexed-stub bytes.
  override fun onStubIndexVersionChanged(indexId: StubIndexKey<*, *>) {
    store?.clear(LocalIndexAllowlist.STUBS)
  }

  override fun initialize(indexLayoutId: String?): InitializationResult {
    layoutId = indexLayoutId
    store?.close()
    store = LocalIndexStore.open(PathManager.getIndexRoot().resolve(ROOT), MAX_BYTES)
    return InitializationResult.SUCCESSFULLY
  }

  override fun attachData(project: Project) = Unit

  override fun getPersistentStateRoot(): String = ROOT

  override fun resetPersistentState() {
    store?.clearAll()
  }

  // The platform calls this after it dropped the data of a broken index (FileBasedIndexImpl.initIndexStorage).
  override fun resetPersistentState(indexId: ID<*, *>) {
    store?.clear(indexId.name)
  }

  override fun shutdown() {
    store?.close()
    store = null
  }

  // -1 so that this extension never changes the version of the indexes it does not wrap
  override fun getVersion(): Int = -1

  companion object {
    const val ROOT = "phantom-local-index"
    private const val MAX_BYTES = 2048L shl 20

    fun getInstance(): LocalIndexExtension = FileBasedIndexInfrastructureExtension.EP_NAME.findExtensionOrFail(LocalIndexExtension::class.java)
  }
}

/**
 * Identifies what the ids inside cached stub bytes mean: the registered stub indexes with their versions. The
 * serializer name storage (`rep.names`) is not part of it: every platform path that recreates it (repair at open,
 * repair followed by a rebuild) ends in `UpdatableIndex.clear` or `resetPersistentState(indexId)`, and both empty
 * the Stubs entries of this cache.
 */
internal fun stubFingerprint(): Long {
  val indexes = StubIndexExtension.EP_NAME.extensionList.map { "${it.key.name}:${it.version}" }.sorted()
  return indexes.joinToString("\n").hashCode().toLong()
}
