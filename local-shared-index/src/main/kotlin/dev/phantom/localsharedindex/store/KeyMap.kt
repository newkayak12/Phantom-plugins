// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localsharedindex.store

/**
 * The in-memory key map of one index: key to [Location], for about a million keys in a large project.
 *
 * A `ConcurrentHashMap<KeyBytes, Location>` costs about 130 bytes per key (node, key object, key array, location
 * object, table slot). This map keeps a 64-bit fingerprint of the key and the location in three parallel arrays, about
 * 50 bytes per key (measured: 57 MB for 1.14 million keys). Two keys with the same fingerprint share an entry, so one can replace the other.
 * A lookup can then find the other key's location. [Segment.read] compares the key stored in the record, so that case
 * is a miss, never a wrong value.
 *
 * Thread-safe: the map is split into shards, and each shard is locked for every operation.
 */
internal class KeyMap {
  private val shards = Array(SHARDS) { Shard() }

  private fun shard(key: KeyBytes) = shards[(key.fingerprint ushr (Long.SIZE_BITS - SHARD_BITS)).toInt()]

  operator fun get(key: KeyBytes): Location? = shard(key).get(key.fingerprint)

  operator fun set(key: KeyBytes, location: Location) = shard(key).put(key.fingerprint, location)

  fun remove(key: KeyBytes) = shard(key).remove(key.fingerprint, null)

  /** Removes the key only while it maps to [location]. */
  fun remove(key: KeyBytes, location: Location) = shard(key).remove(key.fingerprint, location)

  /** Points the key at [new] only while it maps to [old]. */
  fun replace(key: KeyBytes, old: Location, new: Location) = shard(key).replace(key.fingerprint, old, new)

  fun clear() = shards.forEach { it.clear() }

  /** Moves every location in segment [old] to segment [new] (the same file renamed). */
  fun renameGeneration(old: Long, new: Long) = shards.forEach { it.renameGeneration(old, new) }

  /** Forgets every key whose record is in segment [generation]. */
  fun removeGeneration(generation: Long) = shards.forEach { it.removeGeneration(generation) }

  val size: Int get() = shards.sumOf { it.size() }

  /** Open addressing with linear probing; fingerprint 0 marks an empty slot. */
  @Suppress("TooManyFunctions") // the map operations plus the probing helpers they share under one lock
  private class Shard {
    private var fingerprints = LongArray(INITIAL_CAPACITY)
    private var generations = LongArray(INITIAL_CAPACITY)
    private var offsetsAndLengths = LongArray(INITIAL_CAPACITY) // offset in the high 32 bits, length in the low
    private var count = 0

    private val mask get() = fingerprints.size - 1

    @Synchronized fun size() = count

    @Synchronized fun get(fingerprint: Long): Location? {
      val i = find(fingerprint)
      return if (i < 0) null else location(i)
    }

    @Synchronized fun put(fingerprint: Long, location: Location) {
      // Offsets are below the segment size, far from 2 GB; a location that does not fit is not cached
      if (location.offset !in 0..Int.MAX_VALUE.toLong()) return remove(fingerprint, null)
      if ((count + 1) * LOAD_DENOMINATOR > fingerprints.size * LOAD_NUMERATOR) resize(fingerprints.size * 2)
      var i = home(fingerprint)
      while (fingerprints[i] != 0L && fingerprints[i] != fingerprint) i = (i + 1) and mask
      if (fingerprints[i] == 0L) count++
      store(i, fingerprint, location)
    }

    @Synchronized fun remove(fingerprint: Long, expected: Location?) {
      val i = find(fingerprint)
      if (i < 0 || (expected != null && location(i) != expected)) return
      deleteAt(i)
    }

    @Synchronized fun replace(fingerprint: Long, old: Location, new: Location) {
      val i = find(fingerprint)
      if (i < 0 || location(i) != old) return
      if (new.offset !in 0..Int.MAX_VALUE.toLong()) deleteAt(i) else store(i, fingerprint, new)
    }

    @Synchronized fun clear() {
      fingerprints = LongArray(INITIAL_CAPACITY)
      generations = LongArray(INITIAL_CAPACITY)
      offsetsAndLengths = LongArray(INITIAL_CAPACITY)
      count = 0
    }

    @Synchronized fun renameGeneration(old: Long, new: Long) {
      for (i in fingerprints.indices) {
        if (fingerprints[i] != 0L && generations[i] == old) generations[i] = new
      }
    }

    @Synchronized fun removeGeneration(generation: Long) {
      var kept = 0
      for (i in fingerprints.indices) {
        if (fingerprints[i] != 0L && generations[i] != generation) kept++
      }
      if (kept == count) return
      rebuild(capacityFor(kept)) { it != generation }
    }

    private fun find(fingerprint: Long): Int {
      var i = home(fingerprint)
      while (true) {
        val f = fingerprints[i]
        if (f == 0L) return -1
        if (f == fingerprint) return i
        i = (i + 1) and mask
      }
    }

    private fun home(fingerprint: Long) = (fingerprint xor (fingerprint ushr 29)).toInt() and mask

    private fun location(i: Int) = Location(generations[i], offsetsAndLengths[i] ushr 32, offsetsAndLengths[i].toInt())

    private fun store(i: Int, fingerprint: Long, location: Location) {
      fingerprints[i] = fingerprint
      generations[i] = location.generation
      offsetsAndLengths[i] = (location.offset shl 32) or (location.length.toLong() and 0xFFFF_FFFFL)
    }

    /** Backward-shift deletion: moves later entries of the probe run into the gap, so lookups need no tombstones. */
    private fun deleteAt(slot: Int) {
      var hole = slot
      var j = slot
      while (true) {
        j = (j + 1) and mask
        val f = fingerprints[j]
        if (f == 0L) break
        // The entry at j may fill the hole unless its home lies cyclically in (hole, j]
        if (((j - home(f)) and mask) >= ((j - hole) and mask)) {
          fingerprints[hole] = f
          generations[hole] = generations[j]
          offsetsAndLengths[hole] = offsetsAndLengths[j]
          hole = j
        }
      }
      fingerprints[hole] = 0L
      count--
    }

    private fun resize(capacity: Int) = rebuild(capacity) { true }

    /** Re-inserts the entries whose generation passes [keep] into new arrays of [capacity] slots. */
    private inline fun rebuild(capacity: Int, keep: (generation: Long) -> Boolean) {
      val oldFingerprints = fingerprints
      val oldGenerations = generations
      val oldOffsetsAndLengths = offsetsAndLengths
      fingerprints = LongArray(capacity)
      generations = LongArray(capacity)
      offsetsAndLengths = LongArray(capacity)
      count = 0
      for (i in oldFingerprints.indices) {
        val f = oldFingerprints[i]
        if (f == 0L || !keep(oldGenerations[i])) continue
        var j = home(f)
        while (fingerprints[j] != 0L) j = (j + 1) and mask
        fingerprints[j] = f
        generations[j] = oldGenerations[i]
        offsetsAndLengths[j] = oldOffsetsAndLengths[i]
        count++
      }
    }

    private fun capacityFor(entries: Int): Int {
      var capacity = INITIAL_CAPACITY
      while (entries * LOAD_DENOMINATOR > capacity * LOAD_NUMERATOR) capacity *= 2
      return capacity
    }
  }

  companion object {
    private const val SHARD_BITS = 6
    private const val SHARDS = 1 shl SHARD_BITS
    private const val INITIAL_CAPACITY = 16
    // At most 3/4 full
    private const val LOAD_NUMERATOR = 3
    private const val LOAD_DENOMINATOR = 4

    /** A 64-bit fingerprint of all key bytes, never 0 (0 marks an empty slot). */
    fun fingerprint(key: ByteArray): Long {
      var h = -0x61c8864680b583ebL // golden ratio
      for (b in key) h = (h xor (b.toLong() and 0xFF)) * -0x4b47d5b1b0b2aa2bL // FNV-style multiply, 64-bit odd constant
      h = (h xor (h ushr 33)) * -0xae502812aa7333L
      h = (h xor (h ushr 33)) * -0x3b314601e57a13adL
      h = h xor (h ushr 33)
      return if (h == 0L) 1L else h
    }
  }
}
