// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localsharedindex

import com.intellij.util.indexing.IdFilter
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Keys an index lists for an [IdFilter] only through aliases. MapReduceIndexBase.processAllKeys of an index that traces
 * key hashes (KeyHashLog) keeps only keys logged for files the filter accepts, and only donors are logged: a donor in
 * another checkout is outside the filter of the checkout holding its aliases. Widening the filter instead gave it the
 * scope type OTHER, which turns KeyHashLog's per-project cache off: every Goto Class call read the whole key log again
 * (111-390 ms median against 23-36 ms without aliases in a real IDE).
 *
 * The keys of one donor are read once ([keysOf]) and kept while it is recorded with the same content
 * ([IndexAliases.donorToken]); the set for a filter is kept while no alias is added or dropped
 * ([IndexAliases.modificationCount]). A donor's data only changes through an update, which drops its aliases first
 * (CachingIndex.aliasOnUpdate), so the index's own modification stamp is not needed in the key.
 */
internal class BorrowedKeys<K>(private val aliases: IndexAliases, private val keysOf: (donor: Int) -> Collection<K>) {
  private class DonorKeys<K>(val token: Any, val keys: Collection<K>)

  private class ForFilter<K>(val epoch: Long, val keys: Set<K>)

  private val byDonor = ConcurrentHashMap<Int, DonorKeys<K>>()

  // Filters are cached by the platform per project (IdFilter.getProjectIdFilter), so identity is the right key.
  private val byFilter: MutableMap<IdFilter, ForFilter<K>> = Collections.synchronizedMap(WeakHashMap())

  fun of(filter: IdFilter): Set<K> {
    val epoch = aliases.modificationCount
    byFilter[filter]?.takeIf { it.epoch == epoch }?.let { return it.keys }
    val result = HashSet<K>()
    aliases.forEachAliased { donor, aliasIds ->
      if (!filter.containsFileId(donor) && aliasIds.any(filter::containsFileId)) result += keysFor(donor)
    }
    byFilter[filter] = ForFilter(epoch, result)
    return result
  }

  private fun keysFor(donor: Int): Collection<K> {
    val token = aliases.donorToken(donor) ?: return emptyList()
    byDonor[donor]?.takeIf { it.token === token }?.let { return it.keys }
    return keysOf(donor).toList().also { byDonor[donor] = DonorKeys(token, it) }
  }
}
