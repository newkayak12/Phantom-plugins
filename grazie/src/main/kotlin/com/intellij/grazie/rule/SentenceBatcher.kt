package com.intellij.grazie.rule

import ai.grazie.nlp.langs.Language
import ai.grazie.rules.util.BatchParser
import ai.grazie.text.exclusions.SentenceWithExclusions
import com.intellij.grazie.utils.HighlightingUtil
import com.intellij.openapi.progress.runBlockingCancellable

// phantom: what is left without Grazie Cloud. The batching of sentences into server requests (cloud parsing, cloud GEC and
// spelling) is gone; the local dependency parser and the rule engine only need the parser interface and the language lookup.
object SentenceBatcher {
  interface AsyncBatchParser<T> : BatchParser<T> {
    override fun parse(sentences: List<String>): LinkedHashMap<String, T?> =
      runBlockingCancellable {
        parseAsync(sentences.map { SentenceWithExclusions(it) })
      }
        .mapKeysTo(LinkedHashMap()) { it.key.sentence }

    suspend fun parseAsync(sentences: List<SentenceWithExclusions>): LinkedHashMap<SentenceWithExclusions, T?>
  }

  @JvmStatic
  fun findInstalledLTLanguage(language: Language): org.languagetool.Language? {
    return HighlightingUtil.findInstalledLang(language)?.jLanguage
  }
}
