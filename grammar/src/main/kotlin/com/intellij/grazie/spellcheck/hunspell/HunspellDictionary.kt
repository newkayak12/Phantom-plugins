// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.intellij.grazie.spellcheck.hunspell

import com.intellij.grazie.jlanguage.hunspell.LuceneHunspell
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.spellchecker.dictionary.Dictionary
import com.intellij.spellchecker.dictionary.Dictionary.LookupStatus
import com.intellij.util.Consumer
import java.io.File

data class HunspellBundle(val dic: File, val aff: File) {
  fun dictionaryExists(): Boolean = dic.exists() && aff.exists()
}

/** A custom Hunspell dictionary (a .dic file with its .aff) the user added in the spelling settings */
class HunspellDictionary(path: String, name: String? = null) : Dictionary {
  companion object {
    fun getHunspellBundle(dicPath: String): HunspellBundle {
      val path = FileUtilRt.getNameWithoutExtension(dicPath)
      return HunspellBundle(File("$path.dic"), File("$path.aff"))
    }

    fun isHunspell(path: String): Boolean {
      return getHunspellBundle(path).dictionaryExists()
    }
  }

  private val name: String = name ?: path
  private val hunspell: LuceneHunspell
  private val letters = HashSet<Int>()

  init {
    val paths = getHunspellBundle(path)
    check(paths.dictionaryExists()) { "File '$path' not found" }
    hunspell = paths.aff.inputStream().use { aff -> paths.dic.inputStream().use { dic -> LuceneHunspell(aff, dic) } }
    paths.dic.useLines { lines ->
      lines.drop(1).forEach { line -> line.takeWhile { it != ' ' && it != '/' && it != '\t' }.lowercase().chars().forEach { letters.add(it) } }
    }
  }

  override fun getName() = name

  override fun lookup(word: String): LookupStatus {
    // a word with letters the dictionary doesn't have is in another language
    if (word.lowercase().chars().anyMatch { Character.isLetter(it) && it !in letters }) return LookupStatus.Alien
    if (hunspell.spell(word) || hunspell.spell(word.lowercase())) return LookupStatus.Present
    return LookupStatus.Absent
  }

  override fun consumeSuggestions(word: String, consumer: Consumer<String>) {
    for (suggestion in hunspell.suggest(word)) {
      consumer.consume(suggestion)
    }
  }

  override fun getWords(): MutableSet<String> = throw UnsupportedOperationException()
}
