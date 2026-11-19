// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.grazie.spellcheck.dictionary

import com.intellij.grazie.GrazieConfig
import com.intellij.grazie.detection.toLanguage
import com.intellij.grazie.nlp.Alphabet
import com.intellij.grazie.nlp.Levenshtein
import com.intellij.grazie.spellcheck.GrazieCheckers
import com.intellij.openapi.components.service
import com.intellij.spellchecker.dictionary.Dictionary
import com.intellij.spellchecker.dictionary.Dictionary.LookupStatus.Present

/**
 * The dictionaries the platform spellchecker hands to the engine (bundled, project, application and custom ones, and the
 * runtime dictionaries of other plugins), together with the word lists loaded from files.
 */
internal class WordListAdapter : WordList, EditableWordListAdapter() {
  /**
   * A word the spellchecker can't judge: none of its letters are English ones, and no enabled language with a speller is
   * written in its alphabet (a Korean word in an English-only setup, for one)
   */
  fun isAlien(word: String): Boolean {
    if (Alphabet.ENGLISH.matchAny(word)) {
      return false
    }

    val state = GrazieConfig.get()
    val matchedLanguages = state.enabledLanguages.filter { it.toLanguage().alphabet.matchEntire(word) }
    if (matchedLanguages.isEmpty()) {
      return !aggregator.contains(word)
    }

    val hasSpeller = service<GrazieCheckers>().hasSpellerTool(matchedLanguages)
    return !hasSpeller && !aggregator.contains(word)
  }

  override fun contains(word: String, caseSensitive: Boolean): Boolean =
    containsInDictionaries(word, caseSensitive) || aggregator.contains(word, caseSensitive)

  private fun containsInDictionaries(word: String, caseSensitive: Boolean): Boolean =
    if (caseSensitive) {
      dictionaries.values.any { it.lookup(word) == Present }
    }
    else {
      val lowered = word.lowercase()
      // NOTE: dictionary may not contain a lowercase form, but may contain any form in a different case
      // current dictionary interface does not support caseSensitive
      dictionaries.values.any { it.lookup(lowered) == Present || it.lookup(word) == Present }
    }

  override fun suggest(word: String): LinkedHashSet<String> {
    val result = LinkedHashSet<String>()
    suggest(result, word, dictionaries.values)
    result.addAll(aggregator.suggest(word))
    result.remove("")
    return result
  }

  private fun suggest(result: LinkedHashSet<String>, word: String, dictionaries: Collection<Dictionary>) {
    for (dictionary in dictionaries) {
      dictionary.consumeSuggestions(word) {
        if (it.isEmpty()) {
          return@consumeSuggestions
        }
        val distance = Levenshtein.distance(word, it, SimpleWordList.MAX_LEVENSHTEIN_DISTANCE + 1)
        if (distance <= SimpleWordList.MAX_LEVENSHTEIN_DISTANCE) {
          result.add(it)
        }
      }
    }
  }
}
