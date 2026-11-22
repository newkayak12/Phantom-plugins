// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.intellij.grazie.config

import com.intellij.grazie.nlp.Language
import com.intellij.grazie.nlp.LanguageDetector
import com.intellij.util.xmlb.annotations.Property
import java.util.concurrent.ConcurrentHashMap

object DetectionContext {
  data class State(@Property val disabled: Set<Language> = HashSet()) {
    /** Disable from detection */
    fun disable(langs: Iterable<Language>) = State(disabled + langs)

    /** Enable for detection */
    fun enable(langs: Iterable<Language>) = State(disabled - langs)
  }

  data class Local(val counter: ConcurrentHashMap<Language, Int> = ConcurrentHashMap()) {
    companion object {
      //Each text gives SIZE / SCORE_SIZE + 1 scores to own language.
      //If LANGUAGE_SCORES / TOTAL_SCORES > NOTIFICATION_PROPORTION_THRESHOLD we suggest language
      const val SCORE_SIZE = 100

      const val NOTIFICATION_PROPORTION_THRESHOLD = 0.20
      const val NOTIFICATION_TOTAL_THRESHOLD = 3

      //Require not less than 40 chars
      const val TEXT_SIZE_THRESHOLD = 40

      //Require not less than 4 words for non-hieroglyphic languages
      const val WORDS_SIZE_THRESHOLD = 4
    }

    fun getToNotify(disabled: Set<Language>): Set<Language> {
      val total = counter.values.sum()

      val filtered = counter.filter { (_, myTotal) ->
        myTotal > NOTIFICATION_TOTAL_THRESHOLD && (myTotal.toDouble() / total) > NOTIFICATION_PROPORTION_THRESHOLD
      }.map { it.key }

      val langs = filtered.filter { it != Language.UNKNOWN && it !in disabled }

      return langs.toSet()
    }

    fun update(size: Int, wordsTotal: Int, details: LanguageDetector.Result) {
      val language = details.preferred

      //Check if not unknown, and the detector is sure enough
      if (language == Language.UNKNOWN || !details.isReliable) return

      //Check threshold by text size is not met
      if (size < TEXT_SIZE_THRESHOLD) return
      //Check threshold by number of words is not met (if language has words at all)
      if (language.hasWhitespaces && wordsTotal < WORDS_SIZE_THRESHOLD) return

      count(size, language)
    }

    private fun count(size: Int, lang: Language) {
      counter[lang] = counter.getOrDefault(lang, 0) + (size / SCORE_SIZE + 1)
    }

    fun clear() {
      counter.clear()
    }
  }
}
