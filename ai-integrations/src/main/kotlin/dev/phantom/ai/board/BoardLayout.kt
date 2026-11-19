// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.board

/** Where a card was last placed: its column name and its position in it. */
data class Placement(val column: String?, val order: Int)

/** One card position to store: card key (worktree path), column and index within it. */
data class CardPlacement(val card: String, val column: String, val order: Int)

/**
 * The board: ordered columns, each an ordered list of card keys (worktree paths). Pure; no IDE access.
 * A card whose stored column is unknown (or absent) lands in the first column.
 */
class BoardLayout private constructor(val columns: List<String>, private val cards: Map<String, List<String>>) {
  fun cardsOf(column: String): List<String> = cards[column].orEmpty()

  fun columnOf(card: String): String? = columns.firstOrNull { card in cardsOf(it) }

  /** Moves [card] to [column] at [index] (clamped), within or between columns; an unknown column means the first. */
  fun move(card: String, column: String, index: Int): BoardLayout {
    if (columnOf(card) == null) return this
    val target = column.takeIf { it in columns } ?: columns.first()
    val next = columns.associateWith { c -> cards[c].orEmpty().filter { it != card } }.toMutableMap()
    val list = next.getValue(target).toMutableList()
    list.add(index.coerceIn(0, list.size), card)
    next[target] = list
    return BoardLayout(columns, next)
  }

  /** Every card with its column and index, for persisting. */
  fun placements(): List<CardPlacement> =
    columns.flatMap { c -> cardsOf(c).mapIndexed { i, card -> CardPlacement(card, c, i) } }

  companion object {
    fun of(columns: List<String>, cards: List<String>, placement: (String) -> Placement?): BoardLayout {
      val cols = columns.filter { it.isNotBlank() }.distinct().ifEmpty { listOf("Todo") }
      val placed = cards.distinct().mapIndexed { i, card -> Triple(card, placement(card), i) }
      fun columnFor(p: Placement?) = p?.column?.takeIf { it in cols } ?: cols.first()
      val byColumn = placed.groupBy { columnFor(it.second) }
      val ordered = cols.associateWith { c ->
        byColumn[c].orEmpty()
          .sortedWith(compareBy({ it.second == null }, { it.second?.order ?: 0 }, { it.third }))
          .map { it.first }
      }
      return BoardLayout(cols, ordered)
    }
  }
}
