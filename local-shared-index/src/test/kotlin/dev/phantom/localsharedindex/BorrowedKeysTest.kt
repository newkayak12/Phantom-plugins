// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localsharedindex

import com.intellij.util.indexing.IdFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BorrowedKeysTest {
  private val aliases = IndexAliases()
  private val reads = ArrayList<Int>()
  private val borrowed = BorrowedKeys(aliases) { donor -> reads += donor; listOf("k$donor") }

  // A checkout's filter: its own files 100..199; donors 1..9 live in another checkout.
  private val filter = object : IdFilter() {
    override fun containsFileId(id: Int) = id in 100..199
  }

  private fun donor(id: Int, content: Int) = aliases.recordDonor(ByteArray(20) { content.toByte() }, id, 10, "F.java")

  @Test
  fun `test keys of donors outside the filter with aliases inside it`() {
    donor(1, 1)
    donor(2, 2)
    donor(150, 3) // a donor inside the filter lists its keys itself
    aliases.alias(101, 1)
    aliases.alias(300, 2) // an alias outside the filter borrows nothing for it
    aliases.alias(102, 150)
    assertEquals(setOf("k1"), borrowed.of(filter))
  }

  @Test
  fun `test base listing gets the original filter`() {
    var passed: IdFilter? = null
    val keys = ArrayList<String>()
    donor(1, 1)
    aliases.alias(101, 1)
    processWithBorrowedKeys(borrowed, filter, { keys.add(it) }) {
      passed = filter
      keys.add("own")
    }
    assertSame(filter, passed)
    assertEquals(listOf("own", "k1"), keys)
  }

  @Test
  fun `test repeated listing reads nothing again`() {
    for (i in 1..9) donor(i, i)
    for (i in 1..9) aliases.alias(100 + i, i)
    val first = borrowed.of(filter)
    assertSame(first, borrowed.of(filter))
    assertEquals((1..9).toList(), reads.sorted())
    // A new alias recomputes the set, but only the new donor is read.
    donor(20, 20)
    aliases.alias(120, 20)
    assertEquals(first + "k20", borrowed.of(filter))
    assertEquals((1..9).toList() + 20, reads.sorted())
    // A donor recorded with other content is read again.
    aliases.dropDonor(3)
    donor(3, 33)
    aliases.alias(103, 3)
    borrowed.of(filter)
    assertEquals(2, reads.count { it == 3 })
  }
}
