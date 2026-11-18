// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.grazie.spellcheck.engine

import com.intellij.grazie.nlp.Levenshtein
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.containers.CollectionFactory
import morfologik.speller.Speller
import morfologik.stemming.Dictionary
import java.util.concurrent.ConcurrentHashMap

private val LOG = logger<EnglishSpeller>()

/**
 * English spelling with the dictionaries LanguageTool's own English speller uses: the morfologik word lists of
 * org.languagetool:english-pos-dict (en_US, en_GB, en_CA, en_AU, built from the open SCOWL/Hunspell word lists), and
 * the words LanguageTool's language-en accepts on top of them. A word is correct if any variant has it.
 */
internal object EnglishSpeller {
  private const val RESOURCES = "/org/languagetool/resource/en/hunspell"

  private val dictionaries = ConcurrentHashMap<String, Dictionary?>()

  // morfologik's Speller keeps per-call state; one per thread and variant
  private val spellers = ThreadLocal.withInitial { HashMap<String, Speller>() }

  /** Words LanguageTool's English speller accepts beyond the dictionaries (names, tech words, contractions without apostrophe) */
  private val extraWords: Set<String> by lazy {
    val words = CollectionFactory.createSmallMemoryFootprintSet<String>()
    for (file in listOf("spelling.txt", "spelling_custom.txt", "ignore.txt")) {
      EnglishSpeller::class.java.getResourceAsStream("$RESOURCES/$file")?.bufferedReader()?.useLines { lines ->
        lines.map { it.substringBefore('#').trim() }
          .filter { it.isNotEmpty() && ' ' !in it }
          .forEach { words.add(it.substringBefore('/')) }
      }
    }
    words
  }

  // All variants, as one English dictionary: "colour" and "color" are both correct words. Which spelling a text should use
  // is up to the grammar checker (LanguageTool's variant rules and the Oxford spelling option)
  private val variants = listOf("en_US", "en_GB", "en_CA", "en_AU")

  private fun dictionary(variant: String): Dictionary? = dictionaries.computeIfAbsent(variant) {
    try {
      EnglishSpeller::class.java.getResource("$RESOURCES/$variant.dict")?.let { Dictionary.read(it) }
    }
    catch (e: Exception) {
      LOG.warn("Can't read the $variant spelling dictionary", e)
      null
    }
  }

  private fun speller(variant: String): Speller? {
    val dictionary = dictionary(variant) ?: return null
    return spellers.get().getOrPut(variant) { Speller(dictionary, 2) }
  }

  /** Whether the word is in a dictionary, in any letter case: "february" and "FEBRUARY" are as correct as "February" */
  fun isCorrect(word: String): Boolean {
    val base = stripContraction(word)
    val forms = linkedSetOf(base, base.lowercase(), base.lowercase().replaceFirstChar { it.uppercaseChar() })
    if (forms.any { it in extraWords }) return true
    if (inDictionaries(forms)) return true
    // derived words the dictionaries don't list: misconfigured, unregistered, multithreaded, ...
    val lower = base.lowercase()
    val prefix = prefixes.firstOrNull { lower.startsWith(it) && lower.length - it.length >= 4 } ?: return false
    val rest = lower.substring(prefix.length).removePrefix("-")
    return rest in extraWords || inDictionaries(listOf(rest))
  }

  private val prefixes = listOf("anti", "auto", "inter", "multi", "non", "mis", "sub", "un", "re")

  private fun inDictionaries(forms: Collection<String>): Boolean = variants.any { variant ->
    val speller = speller(variant) ?: return@any true
    forms.any { !speller.isMisspelled(it) }
  }

  /** Suggestions from the dictionaries, the closest first */
  fun suggest(word: String, max: Int): List<String> {
    val result = LinkedHashSet<String>()
    for (variant in variants) {
      val speller = speller(variant) ?: continue
      speller.findReplacements(word).forEach { result.add(it) }
    }
    return result.asSequence()
      .withIndex()
      .sortedWith(compareBy({ Levenshtein.distance(word.lowercase(), it.value.lowercase(), 4) }, { it.index }))
      .map { it.value }
      .take(max)
      .toList()
  }

  private val contractions = listOf("n't", "'s", "'ll", "'re", "'ve", "'d", "'m")

  // the dictionaries have no contractions or possessives: "doesn't" is checked as "does", "John's" as "John"
  private fun stripContraction(word: String): String {
    val normalized = word.replace('’', '\'').removeSuffix("'") // girls', teachers'
    if (normalized.length != word.length) return stripContraction(normalized)
    if (normalized.equals("can't", ignoreCase = true) || normalized.equals("won't", ignoreCase = true)) return "will"
    for (suffix in contractions) {
      if (normalized.length > suffix.length + 1 && normalized.endsWith(suffix, ignoreCase = true)) return normalized.dropLast(suffix.length)
    }
    return word
  }
}
