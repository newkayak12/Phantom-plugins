package com.intellij.grazie.spellcheck

import ai.grazie.nlp.langs.LanguageWithVariant
import ai.grazie.nlp.tokenizer.Tokenizer
import ai.grazie.nlp.utils.checkedEndExclusive
import ai.grazie.rules.common.KnownPhrases
import ai.grazie.spell.Speller
import ai.grazie.spell.text.TextSpeller
import ai.grazie.spell.text.Typo
import ai.grazie.text.TextRange
import com.intellij.grazie.GrazieConfig
import com.intellij.grazie.spellcheck.engine.GrazieSpellCheckerEngine
import com.intellij.grazie.text.ExternalTextChecker
import com.intellij.grazie.text.Rule
import com.intellij.grazie.text.TextChecker.ProofreadingContext
import com.intellij.grazie.text.TextProblem
import com.intellij.grazie.utils.EXTRACTOR_SOURCE
import com.intellij.grazie.utils.getGrazieTracker
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.text.StringUtil.BombedCharSequence
import com.intellij.psi.PsiFile
import com.intellij.spellchecker.inspections.IdentifierSplitter.MINIMAL_TYPO_LENGTH
import com.intellij.spellchecker.inspections.SpellCheckingInspection.SpellCheckingScope
import com.intellij.spellchecker.tokenizer.SpellcheckingStrategy.getSpellcheckingStrategy
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private val spellingKey = Key.create<CachedResults>("grazie.text.spell.problems")

internal class SpellingTextChecker : ExternalTextChecker() {
  override fun getRules(locale: Locale): Collection<Rule> = emptyList()

  override fun check(context: ProofreadingContext): Collection<TypoProblem> =
    doCheck(context) {
      findLocalTypos(context, it)
    }

  // phantom: local only; the Grazie Cloud spelling results that used to replace the local ones are gone
  override suspend fun checkExternally(context: ProofreadingContext): Collection<TypoProblem> =
    doCheck(context) {
      findLocalTypos(context, it)
    }

  fun checkContexts(contexts: List<ProofreadingContext>): Collection<TextProblem> {
    return doCheck(contexts) { contexts, project ->
      findLocalTypos(contexts, project)
    }
  }

  private inline fun doCheck(context: ProofreadingContext, action: (Project) -> List<TypoProblem>): List<TypoProblem> {
    val file = context.text.containingFile
    val scopes = GrazieSpellCheckingInspection.buildAllowedScopes(file)
    if (!useTextLevelSpellchecking(context, scopes)) return emptyList()

    val configStamp = getGrazieTracker(file).modificationCount
    var cache = getCachedTypos(context, configStamp)
    if (cache == null) {
      cache = action(file.project)
        .filterNot { it.word.length < MINIMAL_TYPO_LENGTH }
        .filterNot { hasUnknownFragments(it) }
      context.text.putUserData(spellingKey, CachedResults(configStamp, cache))
    }
    return cache
  }

  private inline fun doCheck(contexts: List<ProofreadingContext>, action: (List<ProofreadingContext>, Project) -> List<TypoProblem>): Collection<TextProblem> {
    if (contexts.isEmpty()) return emptyList()

    val file = contexts.first().text.containingFile
    val scopes = GrazieSpellCheckingInspection.buildAllowedScopes(file)
    val toCheck = contexts.filter { useTextLevelSpellchecking(it, scopes) }
    if (toCheck.isEmpty()) return emptyList()

    val configStamp = getGrazieTracker(file).modificationCount
    var cache = getCachedTypos(file, configStamp)
    if (cache == null) {
      cache = action(toCheck, file.project)
        .filterNot { it.word.length < MINIMAL_TYPO_LENGTH }
        .filterNot { hasUnknownFragments(it) }
      file.putUserData(spellingKey, CachedResults(configStamp, cache))
    }
    return cache
  }

  private fun useTextLevelSpellchecking(context: ProofreadingContext, scopes: Set<SpellCheckingScope>): Boolean {
    val element = context.text.getUserData(EXTRACTOR_SOURCE) ?: return true
    val strategy = getSpellcheckingStrategy(element)
    return strategy != null && strategy.elementFitsScope(element, scopes) && strategy.useTextLevelSpellchecking(element)
  }

  private fun getCachedTypos(context: ProofreadingContext, configStamp: Long): List<TypoProblem>? {
    val cache = context.text.getUserData(spellingKey)
    if (cache != null && cache.configStamp == configStamp) {
      return cache.problems
    }
    return null
  }

  private fun getCachedTypos(file: PsiFile, configStamp: Long): List<TypoProblem>? {
    val cache = file.getUserData(spellingKey)
    if (cache != null && cache.configStamp == configStamp) {
      return cache.problems
    }
    return null
  }

  private fun getTextSpeller(project: Project): TextSpeller? {
    val speller = GrazieSpellCheckerEngine.getInstance(project).getSpeller() ?: return null
    val enabledLanguages = GrazieConfig.get().enabledLanguages.mapNotNull { it.withVariant }
    val validPhrasesTexts = ConcurrentHashMap<CharSequence, List<TextRange>>()

    return object : TextSpeller(listOf(object : Speller by speller {
      override fun languages(): List<LanguageWithVariant> = enabledLanguages
    })) {
      override fun knownPhraseRanges(text: CharSequence): List<TextRange> =
        validPhrasesTexts.getOrPut(text) { getValidPhraseRanges(text) }

      private fun getValidPhraseRanges(text: CharSequence): List<TextRange> {
        return enabledLanguages
          .asSequence()
          .map { it.base }
          .filter { it in KnownPhrases.SUPPORTED_LANGUAGES }
          .map { lang -> GrazieSpellCheckerEngine.knownPhrases.computeIfAbsent(lang) { KnownPhrases.forLanguage(lang) } }
          .flatMap {
            ProgressManager.checkCanceled()
            it.validPhrases(text)
          }
          .map { TextRange(it.start, it.end) }
          .distinct()
          .toList()
      }
    }
  }

  private fun findLocalTypos(contexts: List<ProofreadingContext>, project: Project): List<TypoProblem> =
    checkText(contexts, project).flatMap { entry -> entry.value.map { toProblem(entry.key, it) } }

  private fun findLocalTypos(context: ProofreadingContext, project: Project): List<TypoProblem> =
    checkText(context, project).map { toProblem(context, it) }

  private fun checkText(context: ProofreadingContext, project: Project): List<Typo> {
    val textSpeller = getTextSpeller(project) ?: return emptyList()
    return textSpeller.checkText(object : BombedCharSequence(context.text) {
      override fun checkCanceled() {
        ProgressManager.checkCanceled()
      }
    })
  }

  private fun checkText(contexts: List<ProofreadingContext>, project: Project): Map<ProofreadingContext, List<Typo>> {
    val textSpeller = getTextSpeller(project) ?: return emptyMap()
    return contexts.associateWith {
      textSpeller.checkText(object : BombedCharSequence(it.text) {
        override fun checkCanceled() {
          ProgressManager.checkCanceled()
        }
      })
    }
  }

  private fun hasUnknownFragments(typo: TypoProblem): Boolean {
    return typo.text.unknownOffsets().any { offset ->
      offset > typo.range.startOffset && offset < typo.range.endOffset
    }
  }
}

private fun toProblem(context: ProofreadingContext, typo: Typo) = TypoProblem(context.text, typo.range, typo.word, false) { typo.fixes }

private data class CachedResults(val configStamp: Long, val problems: List<TypoProblem>)