// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.grazie.utils

import ai.grazie.gec.model.problem.ProblemFix
import ai.grazie.nlp.langs.LanguageISO
import com.intellij.grazie.GrazieConfig
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.TextRange
import kotlinx.coroutines.CancellationException
import java.util.Enumeration

fun ProblemFix.Part.Change.ijRange(): TextRange = TextRange(range.start, range.endExclusive)
fun ai.grazie.text.TextRange.ijRange(): TextRange = TextRange(start, endExclusive)
fun ai.grazie.rules.tree.TextRange.ijRange(): TextRange = TextRange(start, end)
fun TextRange.aiRange(): ai.grazie.text.TextRange = ai.grazie.text.TextRange(startOffset, endOffset)
fun TextRange.treeRange(): ai.grazie.rules.tree.TextRange = ai.grazie.rules.tree.TextRange(startOffset, endOffset)

fun String.trimToNull(): String? = trim().takeIf(String::isNotBlank)

fun <T> Collection<T>.toLinkedSet() = LinkedSet<T>(this)

typealias LinkedSet<T> = LinkedHashSet<T>

val IntRange.length
  get() = endInclusive - start + 1

fun <T> Enumeration<T>.toSet() = toList().toSet()

inline fun <R> catching(block: () -> R): Result<R> {
  try {
    return Result.success(block())
  }
  catch (exception: CancellationException) {
    throw exception
  }
  catch (exception: ProcessCanceledException) {
    throw exception
  }
  catch (exception: Throwable) {
    return Result.failure(exception)
  }
}

internal fun getHunspellLanguages(state: GrazieConfig.State): Set<LanguageISO> = state.dictionaries.asSequence()
  .mapNotNull { it.language() }
  .mapNotNull { LanguageISO.parse(it) }
  .toSet()
