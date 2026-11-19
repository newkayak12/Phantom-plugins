package com.intellij.grazie.jlanguage.hunspell

import com.intellij.openapi.progress.ProgressManager
import org.apache.lucene.analysis.hunspell.Dictionary
import org.apache.lucene.analysis.hunspell.Hunspell
import org.apache.lucene.analysis.hunspell.SuggestionTimeoutException
import org.apache.lucene.analysis.hunspell.TimeoutPolicy
import org.apache.lucene.store.ByteBuffersDirectory
import org.languagetool.rules.spelling.hunspell.HunspellDictionary
import java.io.InputStream

/** A Hunspell dictionary read and checked by Lucene's Hunspell implementation (Apache 2.0, part of the platform) */
class LuceneHunspell(affix: InputStream, dictionary: InputStream) {
  private val dictionary = ByteBuffersDirectory().use { Dictionary(it, "hunspell", affix, dictionary) }

  // a Hunspell is a light view of the (immutable, shareable) dictionary, but not meant for concurrent use
  private fun hunspell() = Hunspell(dictionary, TimeoutPolicy.RETURN_PARTIAL_RESULT) { ProgressManager.checkCanceled() }

  fun spell(word: String): Boolean = hunspell().spell(word)

  fun suggest(word: String): List<String> =
    try {
      hunspell().suggest(word)
    }
    catch (_: SuggestionTimeoutException) {
      emptyList()
    }
}

/** LanguageTool's Hunspell rules check with [LuceneHunspell] instead of the native Hunspell library */
class LuceneHunspellDictionary(dictionary: InputStream, affix: InputStream) : HunspellDictionary {
  private val dict = LuceneHunspell(affix, dictionary)

  override fun spell(word: String): Boolean = dict.spell(word)
  override fun add(word: String): Nothing = throw UnsupportedOperationException()
  override fun suggest(word: String): List<String> = dict.suggest(word)

  @Volatile
  private var closed = false

  override fun isClosed(): Boolean {
    return closed
  }

  override fun close() {
    closed = true
  }
}
