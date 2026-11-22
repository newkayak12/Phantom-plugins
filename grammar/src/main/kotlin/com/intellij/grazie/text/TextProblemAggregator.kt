// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.intellij.grazie.text

import com.intellij.openapi.util.TextRange

/**
 * Merges the problems several checkers found in one text: of problems whose highlighting overlaps, only the most
 * important is kept (grammar before style, then the checker registered first). Spelling problems are kept apart from
 * grammar ones, as they are reported by a different inspection.
 */
object TextProblemAggregator {
  fun aggregate(problems: List<TextProblem>): List<TextProblem> {
    if (problems.size < 2) return problems
    val kept = ArrayList<TextProblem>()
    for (problem in problems.withIndex().sortedWith(compareBy({ -priority(it.value) }, { it.index })).map { it.value }) {
      if (kept.none { it.isSpellingProblem == problem.isSpellingProblem && intersect(it.highlightRanges, problem.highlightRanges) }) {
        kept.add(problem)
      }
    }
    return problems.filter { it in kept }
  }

  private fun priority(problem: TextProblem): Int = if (problem.isStyleLike) 0 else 1

  private fun intersect(a: List<TextRange>, b: List<TextRange>): Boolean = a.any { ra -> b.any { rb -> ra.intersectsStrict(rb) } }
}
