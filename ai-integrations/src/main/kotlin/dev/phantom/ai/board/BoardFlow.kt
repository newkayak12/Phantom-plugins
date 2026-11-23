// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.board

import dev.phantom.ai.core.AgentStatus
import dev.phantom.ai.core.AiState

/** What moving a card does besides moving it. */
enum class BoardStep {
  /** Only moves. */
  NONE,
  /** Makes the worktree if the card has none and starts its agent with the card's prompt. */
  START,
  /** Asks for a follow-up prompt and sends it to the card's session. */
  FOLLOW_UP,
  /** Asks to confirm and optionally removes the worktree; never merges or pushes. */
  FINISH,
  /** Not allowed: the card stays where it is. */
  REJECT,
}

/**
 * The board's columns as steps of the work. Only the default column names carry a step; a column the user named
 * otherwise only holds cards. Pure.
 */
object BoardFlow {
  val TODO = AiState.DEFAULT_COLUMNS[0]
  val IN_PROGRESS = AiState.DEFAULT_COLUMNS[1]
  val IN_REVIEW = AiState.DEFAULT_COLUMNS[2]
  val DONE = AiState.DEFAULT_COLUMNS[3]

  fun isPlanned(card: String): Boolean = card.startsWith(AiState.PLANNED_CARD_PREFIX)

  /** The step of moving a card from [from] to [to]; a [planned] card (no worktree) can only start, wait or be done. */
  fun step(from: String?, to: String, planned: Boolean): BoardStep = when {
    from == to -> BoardStep.NONE
    to == IN_PROGRESS -> if (from == IN_REVIEW && !planned) BoardStep.FOLLOW_UP else BoardStep.START
    to == DONE -> BoardStep.FINISH
    planned && to != TODO -> BoardStep.REJECT
    else -> BoardStep.NONE
  }

  /** A card in In progress whose session has just finished (DONE now, not before) goes to In review. */
  fun reviewAfter(column: String?, before: AgentStatus?, after: AgentStatus?): Boolean =
    column == IN_PROGRESS && after == AgentStatus.DONE && before != AgentStatus.DONE
}
