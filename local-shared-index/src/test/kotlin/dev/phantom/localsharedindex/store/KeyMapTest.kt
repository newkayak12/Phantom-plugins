// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localsharedindex.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class KeyMapTest {
  private fun key(i: Int) = KeyBytes(ByteArray(20).also { it[16] = (i shr 24).toByte(); it[17] = (i shr 16).toByte(); it[18] = (i shr 8).toByte(); it[19] = i.toByte() })

  private fun location(random: Random) = Location(random.nextLong(1, 8), random.nextLong(0, 64L shl 20), random.nextInt(32, 1 shl 20))

  private fun assertSame(expected: Map<Int, Location>, map: KeyMap, keys: Int) {
    assertEquals(expected.size, map.size)
    for (i in 0 until keys) assertEquals("key $i", expected[i], map[key(i)])
  }

  @Test fun `random operations match a HashMap`() {
    val random = Random(42)
    val map = KeyMap()
    val expected = HashMap<Int, Location>()
    val keys = 5_000
    repeat(200_000) {
      val i = random.nextInt(keys)
      val k = key(i)
      when (random.nextInt(10)) {
        in 0..4 -> location(random).let { map[k] = it; expected[i] = it }
        5 -> { map.remove(k); expected.remove(i) }
        6 -> expected[i]?.let { map.remove(k, it); expected.remove(i) }
        7 -> location(random).let { stale -> map.remove(k, stale); if (expected[i] == stale) expected.remove(i) }
        8 -> expected[i]?.let { old -> location(random).let { map.replace(k, old, it); expected[i] = it } }
        else -> location(random).let { map.replace(k, it, location(random)) } // stale: no change
      }
    }
    assertSame(expected, map, keys)
  }

  @Test fun `generations are renamed and removed`() {
    val random = Random(7)
    val map = KeyMap()
    val expected = HashMap<Int, Location>()
    for (i in 0 until 20_000) location(random).let { map[key(i)] = it; expected[i] = it }

    map.renameGeneration(3, 30)
    expected.replaceAll { _, l -> if (l.generation == 3L) l.copy(generation = 30) else l }
    assertSame(expected, map, 20_000)

    map.removeGeneration(5)
    expected.values.removeIf { it.generation == 5L }
    assertSame(expected, map, 20_000)

    map.clear()
    assertEquals(0, map.size)
    assertNull(map[key(1)])
  }

  @Test fun `a location past 2 GB is not kept`() {
    val map = KeyMap()
    map[key(1)] = Location(1, 1L shl 31, 100)
    assertNull(map[key(1)])
    map[key(2)] = Location(1, 10, 100)
    assertEquals(Location(1, 10, 100), map[key(2)])
  }
}
