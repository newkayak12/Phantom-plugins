// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.intellij.grazie.detection

import com.intellij.grazie.config.DetectionContext
import com.intellij.grazie.nlp.Language
import com.intellij.grazie.nlp.LanguageDetector
import com.intellij.grazie.nlp.Words
import com.intellij.grazie.text.TextContent
import com.intellij.grazie.utils.HighlightingUtil
import com.intellij.grazie.utils.HighlightingUtil.getCheckedFileTexts
import com.intellij.grazie.utils.HighlightingUtil.grazieConfigTracker
import com.intellij.grazie.utils.NaturalTextDetector
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager

private typealias DetectionResults = Map<String, LanguageDetector.Result>

/**
 * Detects the languages of all checked texts of a file at once. A text with nothing that tells its language takes the
 * language of the file's other texts when they agree on one: "Fix NPE" among German comments is German too.
 */
object BatchLangDetector {
  private val CACHE = Key.create<CachedValue<ResultHolder>>("grazie reliable language detection cache")

  fun getLanguage(content: TextContent, offset: Int): Language? {
    val text = content.substring(offset).take(LanguageDetector.LIMIT)
    if (!NaturalTextDetector.seemsNatural(text)) return null
    val language = (detectForFile(content.containingFile)[text] ?: LanguageDetector.detect(text)).preferred
    return if (language == Language.UNKNOWN) null else language
  }

  fun updateContext(file: PsiFile, context: DetectionContext.Local) {
    detectForFile(file).forEach { (text, details) ->
      context.update(text.length, Words.of(text).size, details)
    }
  }

  private fun detectForFile(file: PsiFile): DetectionResults =
    CachedValuesManager.getCachedValue(file, CACHE) {
      val texts = getCleanTexts(file)
      val languages = texts.map {
        ProgressManager.checkCanceled()
        LanguageDetector.detect(it)
      }
      CachedValueProvider.Result.create(ResultHolder(file, texts, withFileLanguage(languages)), file, grazieConfigTracker())
    }.get()

  private fun withFileLanguage(results: List<LanguageDetector.Result>): List<LanguageDetector.Result> {
    val reliable = results.filter { it.isReliable }.map { it.preferred }.toSet()
    val fileLanguage = reliable.singleOrNull() ?: return results
    return results.map { result ->
      if (!result.hasEvidence && result.preferred != fileLanguage && result.preferred.alphabet == fileLanguage.alphabet) {
        LanguageDetector.Result(fileLanguage, false)
      }
      else result
    }
  }

  private fun getCleanTexts(file: PsiFile): List<String> =
    getCheckedFileTexts(file.viewProvider)
      .map { it.substring(HighlightingUtil.stripPrefix(it)) }
      .map { it.take(LanguageDetector.LIMIT) }
      .filter { NaturalTextDetector.seemsNatural(it) }

  private data class ResultHolder(val psiFile: PsiFile, val texts: List<String>, val languages: List<LanguageDetector.Result>) {
    override fun toString(): String {
      return "[fileType = ${psiFile.viewProvider.virtualFile.fileType}, " +
             "fileLanguage = ${psiFile.language}, " +
             "viewProviderLanguages = ${psiFile.viewProvider.allFiles.map { it.language }.toSet()}, " +
             "detectedLanguages = ${languages.mapTo(HashSet()) { it.preferred }}," +
             "isPhysical = ${psiFile.isPhysical}, " +
             "contentLengths = ${texts.map { it.length }}]"
    }

    fun get(): DetectionResults = texts.zip(languages).toMap()
  }
}
