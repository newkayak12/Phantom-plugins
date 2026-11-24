// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localsharedindex

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IndexAliasesTest {
  private val aliases = IndexAliases()
  private val gone = HashSet<Int>()
  private val reindexed = HashSet<Int>()
  private var previous: AliasRegistry.Liveness? = null

  @Before
  fun setUp() {
    previous = AliasRegistry.liveness
    AliasRegistry.liveness = object : AliasRegistry.Liveness {
      override fun exists(fileId: Int) = fileId !in gone

      override fun reindex(fileIds: Set<Int>) {
        reindexed += fileIds
      }
    }
  }

  @After
  fun tearDown() {
    AliasRegistry.liveness = previous
  }

  private val key = ByteArray(20) { 7 }

  @Test
  fun `test a gone donor is dropped before it gets an alias`() {
    aliases.recordDonor(key, 1, 10, "F.java")
    aliases.alias(101, 1)
    assertTrue(aliases.isLive(1))
    gone += 1
    assertFalse(aliases.isLive(1))
    assertNull(aliases.donorFor(key))
    assertNull(aliases.donorOf(101))
    assertEquals(setOf(101), reindexed)
  }

  @Test
  fun `test the startup check looks only at donors with aliases`() {
    for (i in 1..5) aliases.recordDonor(ByteArray(20) { i.toByte() }, i, 10, "F$i.java")
    aliases.alias(101, 1)
    val checked = ArrayList<Int>()
    assertEquals(emptySet<Int>(), aliases.dropDonorsWhere { checked += it; false })
    assertEquals(listOf(1), checked)
  }
}
