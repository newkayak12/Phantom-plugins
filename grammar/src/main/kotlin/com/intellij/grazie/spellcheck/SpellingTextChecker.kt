// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.intellij.grazie.spellcheck

import com.intellij.grazie.spellcheck.engine.isLatinHomoglyph
import com.intellij.grazie.text.ExternalTextChecker
import com.intellij.grazie.text.Rule
import com.intellij.grazie.text.TextChecker.ProofreadingContext
import com.intellij.grazie.text.TextProblem
import com.intellij.grazie.utils.EXTRACTOR_SOURCE
import com.intellij.grazie.utils.getGrazieTracker
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.spellchecker.SpellCheckerManager
import com.intellij.spellchecker.inspections.PlainTextSplitter
import com.intellij.spellchecker.inspections.IdentifierSplitter.MINIMAL_TYPO_LENGTH
import com.intellij.spellchecker.inspections.SpellCheckingInspection.SpellCheckingScope
import com.intellij.spellchecker.tokenizer.SpellcheckingStrategy.getSpellcheckingStrategy
import java.util.Locale

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

  private fun findLocalTypos(contexts: List<ProofreadingContext>, project: Project): List<TypoProblem> =
    checkText(contexts, project).flatMap { entry -> entry.value.map { toProblem(entry.key, it) } }

  private fun findLocalTypos(context: ProofreadingContext, project: Project): List<TypoProblem> =
    checkText(context, project).map { toProblem(context, it) }

  private fun checkText(context: ProofreadingContext, project: Project): List<Typo> = checkText(context.text, project)

  private fun checkText(contexts: List<ProofreadingContext>, project: Project): Map<ProofreadingContext, List<Typo>> =
    contexts.associateWith { checkText(it.text, project) }

  // The words as the Typo inspection splits plain text (URLs, e-mails and file paths skipped, identifiers split at their
  // humps), each looked up by the platform spellchecker. A word with a misspelled part is reported whole, so that it can
  // be saved to a dictionary as it is written. Left alone: acronyms (HTML, APISIX) and texts in a language there is no
  // dictionary for.
  private fun checkText(text: CharSequence, project: Project): List<Typo> {
    val string = text.toString()
    if (ForeignText.isForeign(string)) return emptyList()
    val manager = SpellCheckerManager.getInstance(project)
    val parts = ArrayList<TextRange>()
    PlainTextSplitter.getInstance().split(string, TextRange(0, string.length)) { parts.add(it) }

    val typos = ArrayList<Typo>()
    for ((token, tokenParts) in parts.groupBy { wordAround(string, it) }) {
      ProgressManager.checkCanceled()
      val word = token.substring(string)
      if (isAcronym(word)) continue
      val misspelled = if (tokenParts.size == 1 && tokenParts[0] == token) manager.hasProblem(word)
                       else tokenParts.any { manager.hasProblem(it.substring(string)) } && manager.hasProblem(word)
      if (misspelled) {
        typos.add(Typo(token, word) { manager.getSuggestions(word).toCollection(LinkedHashSet()) })
      }
    }
    return typos
  }

  // the word a part belongs to, in the part's script: "loadd이벤트가" is "loadd" followed by Korean
  private fun wordAround(text: String, part: TextRange): TextRange {
    val partText = part.subSequence(text)
    val scriptChar = partText.firstOrNull { it.isLetter() && !isLatinHomoglyph(it) } ?: partText.first()
    val script = Character.UnicodeScript.of(scriptChar.code)
    fun isWordChar(i: Int) = text[i].isDigit() ||
                             text[i].isLetter() && Character.UnicodeScript.of(text[i].code) == script ||
                             script == Character.UnicodeScript.LATIN && isLatinHomoglyph(text[i]) ||
                             (text[i] == '\'' || text[i] == '’') && i > 0 && i + 1 < text.length && text[i - 1].isLetter() && text[i + 1].isLetter()
    var start = part.startOffset
    var end = part.endOffset
    while (start > 0 && isWordChar(start - 1)) start--
    while (end < text.length && isWordChar(end)) end++
    return TextRange(start, end)
  }

  private fun isAcronym(word: String): Boolean = word.count { it.isLetter() } >= 2 && word.none { it.isLowerCase() }

  private fun hasUnknownFragments(typo: TypoProblem): Boolean {
    return typo.text.unknownOffsets().any { offset ->
      offset > typo.range.startOffset && offset < typo.range.endOffset
    }
  }
}

private class Typo(val range: TextRange, val word: String, val fixes: () -> Set<String>)

private fun toProblem(context: ProofreadingContext, typo: Typo) = TypoProblem(context.text, typo.range, typo.word, false) { typo.fixes() }

private data class CachedResults(val configStamp: Long, val problems: List<TypoProblem>)