// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.grazie.spellcheck

import com.intellij.spellchecker.BundledDictionaryProvider

/**
 * Common programming words the English dictionaries don't have (args, href, println, stdout, ...): a word list of this
 * plugin, loaded by the platform spellchecker next to its own bundled dictionaries.
 */
internal class ProgrammingDictionaryProvider : BundledDictionaryProvider {
  override fun getBundledDictionaries(): Array<String> = arrayOf("/dictionary/programming.dic")
}
