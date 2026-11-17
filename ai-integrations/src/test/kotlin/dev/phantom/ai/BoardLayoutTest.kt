// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import dev.phantom.ai.board.BoardLayout
import dev.phantom.ai.board.Placement
import dev.phantom.ai.core.AiState
import dev.phantom.ai.core.AiStateData
import junit.framework.TestCase

class BoardLayoutTest : TestCase() {
  private val cols = listOf("Todo", "Doing", "Done")

  fun testDefaultColumnsAreTheFourStatuses() {
    assertEquals(listOf("Todo", "In progress", "In review", "Done"), AiState.DEFAULT_COLUMNS)
  }

  fun testUnknownOrMissingColumnGoesToFirst() {
    val l = BoardLayout.of(cols, listOf("a", "b", "c")) { if (it == "a") Placement("Gone", 0) else if (it == "b") Placement("Done", 0) else null }
    assertEquals(listOf("a", "c"), l.cardsOf("Todo"))
    assertEquals(listOf("b"), l.cardsOf("Done"))
  }

  fun testOrderWithinColumnFollowsStoredOrder() {
    val l = BoardLayout.of(cols, listOf("a", "b", "c")) { Placement("Doing", mapOf("a" to 2, "b" to 0, "c" to 1).getValue(it)) }
    assertEquals(listOf("b", "c", "a"), l.cardsOf("Doing"))
  }

  fun testMoveWithinColumn() {
    val l = BoardLayout.of(cols, listOf("a", "b", "c")) { Placement("Todo", "abc".indexOf(it[0])) }
    assertEquals(listOf("b", "c", "a"), l.move("a", "Todo", 2).cardsOf("Todo"))
    assertEquals(listOf("c", "a", "b"), l.move("c", "Todo", 0).cardsOf("Todo"))
  }

  fun testMoveBetweenColumnsAndClampsIndex() {
    val l = BoardLayout.of(cols, listOf("a", "b")) { Placement("Todo", "ab".indexOf(it[0])) }
    val m = l.move("a", "Done", 99).move("b", "Done", 0)
    assertEquals(emptyList<String>(), m.cardsOf("Todo"))
    assertEquals(listOf("b", "a"), m.cardsOf("Done"))
    assertEquals("Done", m.columnOf("a"))
  }

  fun testMoveToUnknownColumnGoesFirstAndUnknownCardIsIgnored() {
    val l = BoardLayout.of(cols, listOf("a")) { Placement("Done", 0) }
    assertEquals(listOf("a"), l.move("a", "Nope", 0).cardsOf("Todo"))
    assertSame(l, l.move("zzz", "Todo", 0))
  }

  fun testPlacementsRoundTripThroughAiState() {
    val l = BoardLayout.of(cols, listOf("a", "b", "c")) { null }.move("a", "Done", 0).move("b", "Done", 1)
    val s = AiState()
    s.loadState(AiStateData())
    s.placeCards(l.placements())
    val back = BoardLayout.of(cols, listOf("a", "b", "c")) { p -> s.findWorktree(p)?.let { Placement(it.column, it.order) } }
    assertEquals(listOf("c"), back.cardsOf("Todo"))
    assertEquals(listOf("a", "b"), back.cardsOf("Done"))
  }

  fun testFindWorktreeDoesNotCreateAnEntry() {
    val s = AiState()
    assertNull(s.findWorktree("/x"))
    assertEquals(0, s.state.worktrees.size)
  }
}
