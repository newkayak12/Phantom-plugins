// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import dev.phantom.ai.board.BoardFlow
import dev.phantom.ai.board.BoardStep
import dev.phantom.ai.board.forget
import dev.phantom.ai.board.newCard
import dev.phantom.ai.board.rekey
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentStatus
import dev.phantom.ai.core.AiState
import dev.phantom.ai.core.AiStateData
import junit.framework.TestCase

class BoardFlowTest : TestCase() {
  fun testMovingToInProgressStartsAndBackFromReviewFollowsUp() {
    assertEquals(BoardStep.START, BoardFlow.step("Todo", "In progress", planned = false))
    assertEquals(BoardStep.START, BoardFlow.step("Todo", "In progress", planned = true))
    assertEquals(BoardStep.FOLLOW_UP, BoardFlow.step("In review", "In progress", planned = false))
    assertEquals(BoardStep.START, BoardFlow.step("Done", "In progress", planned = false))
  }

  fun testDoneFinishesAndOtherMovesOnlyMove() {
    assertEquals(BoardStep.FINISH, BoardFlow.step("In review", "Done", planned = false))
    assertEquals(BoardStep.FINISH, BoardFlow.step("Todo", "Done", planned = true))
    assertEquals(BoardStep.NONE, BoardFlow.step("In progress", "In review", planned = false))
    assertEquals(BoardStep.NONE, BoardFlow.step("Done", "Todo", planned = false))
    assertEquals(BoardStep.NONE, BoardFlow.step("Done", "Done", planned = false))
    assertEquals(BoardStep.NONE, BoardFlow.step("Todo", "Someday", planned = false))
  }

  fun testAPlannedCardCannotWaitForReview() {
    assertEquals(BoardStep.REJECT, BoardFlow.step("Todo", "In review", planned = true))
    assertEquals(BoardStep.REJECT, BoardFlow.step("Todo", "Someday", planned = true))
  }

  fun testOnlyAJustFinishedSessionOfAnInProgressCardGoesToReview() {
    assertTrue(BoardFlow.reviewAfter("In progress", AgentStatus.WORKING, AgentStatus.DONE))
    assertTrue(BoardFlow.reviewAfter("In progress", null, AgentStatus.DONE))
    assertFalse(BoardFlow.reviewAfter("In progress", AgentStatus.DONE, AgentStatus.DONE))
    assertFalse(BoardFlow.reviewAfter("In progress", AgentStatus.DONE, AgentStatus.WORKING))
    assertFalse(BoardFlow.reviewAfter("Todo", AgentStatus.WORKING, AgentStatus.DONE))
  }

  fun testPlannedCardIsStoredInTheFirstColumnAndRekeyedToItsWorktree() {
    val s = AiState()
    s.loadState(AiStateData())
    val e = s.newCard("/r/repo", "Login page", "add a login page", AgentKind.CODEX)
    assertTrue(BoardFlow.isPlanned(e.path!!))
    assertEquals("Todo", e.column)
    assertEquals("CODEX", e.agent)
    val key = e.path!!
    s.rekey(key, "/w/login-page")
    assertNull(s.findWorktree(key))
    assertEquals("add a login page", s.findWorktree("/w/login-page")?.prompt)
    s.forget("/w/login-page")
    assertNull(s.findWorktree("/w/login-page"))
  }
}
