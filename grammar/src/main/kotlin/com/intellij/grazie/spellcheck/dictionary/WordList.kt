// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.grazie.spellcheck.dictionary

/** A set of words the spellchecker accepts, which can also suggest its words close to a misspelled one */
interface WordList {
  fun contains(word: String, caseSensitive: Boolean = false): Boolean

  fun suggest(word: String): LinkedHashSet<String>
}
