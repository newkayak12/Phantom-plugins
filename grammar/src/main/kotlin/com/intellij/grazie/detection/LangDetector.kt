// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.grazie.detection

import com.intellij.grazie.GrazieConfig
import com.intellij.grazie.config.DetectionContext
import com.intellij.grazie.jlanguage.Lang
import com.intellij.grazie.nlp.Language
import com.intellij.grazie.nlp.LanguageDetector
import com.intellij.grazie.nlp.Words
import com.intellij.util.containers.ContainerUtil

/**
 * Use [BatchLangDetector] for more accurate results, if possible
 */
object LangDetector {
  private val cache = ContainerUtil.createConcurrentSoftValueMap<String, LanguageDetector.Result>()

  private fun detectWithDetails(textToDetect: String): LanguageDetector.Result {
    require(textToDetect.length <= LanguageDetector.LIMIT)
    return cache.computeIfAbsent(textToDetect) { LanguageDetector.detect(it) }
  }

  /**
   * Get natural language of text.
   *
   * @return Language that is detected.
   */
  fun getLanguage(text: String): Language? {
    val detected = detectWithDetails(text.take(LanguageDetector.LIMIT)).preferred
    return if (detected == Language.UNKNOWN) null else detected
  }

  /**
   * Get natural language of text if it is enabled in Grazie
   *
   * @return Lang that is detected and enabled in grazie
   */
  @Suppress("unused") // preserved to not break binary compatibility
  fun getLang(text: String): Lang? = getLanguage(text)?.let { language ->
    GrazieConfig.get().availableLanguages.find { lang -> lang.equalsTo(language) }
  }

  /**
   * Update local detection context from text
   */
  fun updateContext(text: CharSequence, context: DetectionContext.Local) {
    val textToDetect = text.take(LanguageDetector.LIMIT).toString()
    val details = detectWithDetails(textToDetect)
    context.update(text.length, Words.of(textToDetect).size, details)
  }
}
