// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.grazie.spellcheck

import com.intellij.grazie.GrazieConfig
import com.intellij.grazie.detection.toLangOrNull
import com.intellij.grazie.nlp.Language
import com.intellij.grazie.nlp.LanguageDetector
import com.intellij.openapi.components.service

/**
 * Text clearly written in a language the spellchecker has no dictionary for (German in an English-only setup) is not
 * spellchecked word by word: every word of it would be a typo. The language detection inspection suggests enabling it.
 */
internal object ForeignText {
  fun isForeign(text: CharSequence): Boolean {
    if (text.length < 20) return false
    val result = LanguageDetector.detect(text)
    val language = result.preferred
    if (!result.isReliable || language == Language.ENGLISH || language == Language.UNKNOWN) return false
    val lang = language.toLangOrNull() ?: return true
    return lang !in GrazieConfig.get().enabledLanguages || !service<GrazieCheckers>().hasSpellerTool(listOf(lang))
  }
}
