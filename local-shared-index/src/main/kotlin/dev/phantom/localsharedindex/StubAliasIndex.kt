// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localsharedindex

import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Processor
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.IdFilter
import com.intellij.util.indexing.IndexedFile
import com.intellij.util.indexing.UpdatableIndex
import com.intellij.util.indexing.ValueContainer
import com.intellij.util.indexing.impl.ValueContainerImpl
import com.intellij.util.indexing.impl.ValueContainerProcessor

/**
 * One stub index (StubIndexImpl keeps one per StubIndexKey, each passed through combineIndex) answering for the aliases
 * of the Stubs index too. StubIndex updates these indexes from the stub tree a Stubs update writes, so a file skipped by
 * scanning has no keys here; queries add its id wherever its donor's id is. The alias's stub tree itself is served by
 * [CachingIndex.getIndexedFileData]. Nothing is cached: updates and removals go to [base] untouched.
 */
internal class StubAliasIndex<K, V, D>(
  private val base: UpdatableIndex<K, V, FileContent, D>,
  private val aliases: IndexAliases,
) : UpdatableIndex<K, V, FileContent, D> by base {

  override fun <E : Exception> withData(key: K & Any, processor: ValueContainerProcessor<V, E>): Boolean =
    withAliasData(base, aliases, key, processor)

  override fun processAllKeys(processor: Processor<in K>, scope: GlobalSearchScope, idFilter: IdFilter?): Boolean =
    base.processAllKeys(processor, scope, aliases.widen(idFilter))

  override fun getModificationStamp(): Long = base.modificationStamp + aliases.modificationCount

  // Java interface defaults: forwarded by hand so that the base index sees them, not the interface's empty body.
  override fun setIndexedStateForFileOnFileIndexMetaData(fileId: Int, fileIndexMetaData: D?, isProvidedByInfrastructureExtension: Boolean) =
    base.setIndexedStateForFileOnFileIndexMetaData(fileId, fileIndexMetaData, isProvidedByInfrastructureExtension)

  override fun setIndexedStateForFile(fileId: Int, file: IndexedFile, isProvidedByInfrastructureExtension: Boolean) =
    base.setIndexedStateForFile(fileId, file, isProvidedByInfrastructureExtension)

  override fun canUpdate(): Boolean = base.canUpdate()

  @Deprecated("Deprecated in the platform; answered through withData so that aliases are included")
  override fun getData(key: K & Any): ValueContainer<V> = copyOfData(this, key)
}

/**
 * The base containers first, then one more container with the aliases of every donor seen in them, carrying the
 * donor's value. [com.intellij.util.indexing.InvertedIndex.withData] allows several containers per key (sharded storages
 * pass one per shard).
 */
internal fun <K, V, E : Exception> withAliasData(
  base: UpdatableIndex<K, V, FileContent, *>,
  aliases: IndexAliases?,
  key: K & Any,
  processor: ValueContainerProcessor<V, E>,
): Boolean {
  if (aliases == null || !aliases.hasAliases) return base.withData(key, processor)
  val borrowed = ValueContainerImpl.createNewValueContainer<V>()
  val completed = base.withData<E>(key) { container ->
    container.forEach { id, value ->
      for (alias in aliases.aliasesOf(id)) borrowed.addValue(alias, value)
      true
    }
    processor.process(container)
  }
  if (!completed || borrowed.size() == 0) return completed
  return processor.process(borrowed)
}

internal fun <K, V> copyOfData(index: UpdatableIndex<K, V, FileContent, *>, key: K & Any): ValueContainer<V> {
  val copy = ValueContainerImpl.createNewValueContainer<V>()
  index.withData<RuntimeException>(key) { container ->
    container.forEach { id, value ->
      copy.addValue(id, value)
      true
    }
  }
  return copy
}
