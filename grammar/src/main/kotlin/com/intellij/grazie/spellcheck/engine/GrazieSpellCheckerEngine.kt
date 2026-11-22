// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.intellij.grazie.spellcheck.engine

import com.github.benmanes.caffeine.cache.Caffeine
import com.intellij.grazie.nlp.Alphabet
import com.intellij.grazie.nlp.Levenshtein
import com.intellij.grazie.spellcheck.async.WordListLoader
import com.intellij.grazie.spellcheck.dictionary.WordListAdapter
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.serviceAsync
import com.intellij.openapi.extensions.ExtensionNotApplicableException
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.io.FileUtil
import com.intellij.spellchecker.SpellCheckerManager
import com.intellij.spellchecker.dictionary.Dictionary
import com.intellij.spellchecker.dictionary.EditableDictionary
import com.intellij.spellchecker.dictionary.Loader
import com.intellij.spellchecker.engine.DictionaryModificationTracker
import com.intellij.spellchecker.engine.SpellCheckerEngine
import com.intellij.spellchecker.engine.SpellCheckerEngineListener
import com.intellij.spellchecker.engine.Transformation
import com.intellij.spellchecker.settings.CustomDictionarySettingsListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.TestOnly

internal const val MAX_WORD_LENGTH: Int = 32
private const val MIN_CHECKED_LENGTH: Int = 3

/**
 * The engine behind the platform spellchecker (the Typo inspection and text-level spellchecking): English words are
 * looked up in LanguageTool's English dictionaries ([EnglishSpeller]), everything else in the dictionaries the platform
 * hands over: bundled, application, project and custom ones, and the LanguageTool spellers of other enabled languages.
 */
class GrazieSpellCheckerEngine(private val project: Project, private val coroutineScope: CoroutineScope) : SpellCheckerEngine, Disposable {

  companion object {
    @JvmStatic
    fun getInstance(project: Project): GrazieSpellCheckerEngine = SpellCheckerEngine.getInstance(project) as GrazieSpellCheckerEngine
  }

  override fun getTransformation(): Transformation = Transformation()

  private val manager by lazy { SpellCheckerManager.getInstance(project) }
  private val loader = WordListLoader(project, coroutineScope)
  private val adapter = WordListAdapter()
  private val modificationTracker: DictionaryModificationTracker
    get() = DictionaryModificationTracker.getInstance(project)

  internal class SpellerLoadActivity : ProjectActivity {
    init {
      // Do not preload the speller in test mode, so it won't slow down tests not related to the spellchecker.
      // We will still load it in tests but only when it is actually necessary.
      if (ApplicationManager.getApplication().isUnitTestMode) {
        throw ExtensionNotApplicableException.create()
      }
    }

    override suspend fun execute(project: Project) {
      getInstance(project).initializeSpeller(project)
      project.serviceAsync<SpellCheckerManager>()
    }
  }

  internal class SuggestionCacheInvalidator(private val project: Project) : CustomDictionarySettingsListener {
    override fun customDictionaryPathsChanged(newPaths: List<String>) {
      getInstance(project).suggestionCache.invalidateAll()
    }
  }

  @Volatile
  private var initialized = false

  init {
    if (ApplicationManager.getApplication().isUnitTestMode) {
      initializeSpeller(project)
    }
  }

  private val suggestionCache = Caffeine.newBuilder().maximumSize(1024).build<SuggestionRequest, List<String>> { request ->
    manager.updateBundledDictionaries()
    suggest(request.word, request.maxSuggestions)
  }

  /** Loads the English dictionaries and tells the platform the spellchecker is ready */
  @ApiStatus.Internal
  fun initializeSpeller(project: Project) {
    EnglishSpeller.isCorrect("warmup")
    if (!initialized) {
      initialized = true
      coroutineScope.launch {
        readAction {
          project.messageBus.syncPublisher(SpellCheckerEngineListener.TOPIC).onSpellerInitialized()
        }
      }
    }
  }

  override fun dispose() {
    coroutineScope.cancel()
  }

  override fun isDictionaryLoad(name: String): Boolean = adapter.containsSource(name)

  override fun getDictionaryNames(): Set<String> = adapter.dictionaryNames

  override fun loadDictionary(loader: Loader) {
    this.loader.loadWordList(loader, adapter::addList)
    modificationTracker.incModificationCount()
  }

  override fun addDictionary(dictionary: Dictionary) {
    if (!isDictionaryLoad(dictionary.name)) {
      adapter.addDictionary(dictionary)
      modificationTracker.incModificationCount()
    }
  }

  override fun addModifiableDictionary(dictionary: EditableDictionary) {
    addDictionary(dictionary)
  }

  override fun isCorrect(word: String): Boolean {
    if (word.length < MIN_CHECKED_LENGTH || word.length > MAX_WORD_LENGTH) return true
    if (word.any { it.isDigit() } || word.none { it.isLetter() }) return true
    manager.updateBundledDictionaries()
    if (adapter.isAlien(word) || adapter.contains(word, caseSensitive = false)) return true
    val parts = identifierParts(word)
    if (parts.size > 1) {
      // "CANopen" is CAN + open rather than CA + Nopen: an upper-case run may keep the letter that starts the next word
      return parts.all { isCorrect(word.substring(it)) } ||
             identifierParts(word, acronymTakesNextCapital = true).let { alt -> alt != parts && alt.all { isCorrect(word.substring(it)) } }
    }
    if (Alphabet.LATIN.matchEntire(word)) return EnglishSpeller.isCorrect(word)
    return false
  }

  /** Whether the word can't be judged (none of the enabled languages with a speller is written in its letters) */
  fun isAlien(word: String): Boolean = adapter.isAlien(word)

  override fun getSuggestions(word: String, maxSuggestions: Int, maxMetrics: Int): List<String> {
    if (word.length > MAX_WORD_LENGTH) {
      return emptyList()
    }
    return suggestionCache.get(SuggestionRequest(word, maxSuggestions))
  }

  private fun suggest(word: String, max: Int): List<String> {
    val latin = homoglyphsToLatin(word)
    if (latin != word) {
      // "Сorrect" written with a Cyrillic С: the Latin spelling is the suggestion, if it is a word
      return if (isCorrect(latin)) listOf(latin) else suggest(latin, max)
    }
    val parts = identifierParts(word)
    if (parts.size > 1) return listOfNotNull(suggestForParts(word, parts))
    val fromDictionaries = adapter.suggest(word)
    val english = if (Alphabet.LATIN.matchEntire(word)) EnglishSpeller.suggest(word, max) else emptyList()
    val lowered = word.lowercase()
    val ranked = (english + fromDictionaries)
      .distinct()
      .filter { it != word }
      .map { it to Levenshtein.withTranspositions(lowered, it.lowercase()) }
      .withIndex()
      .sortedWith(compareBy({ it.value.second }, { it.index }))
      .map { it.value }
    // only the closest ones: a suggestion two edits away is noise next to one a single edit away
    val best = ranked.firstOrNull()?.second ?: return emptyList()
    return ranked.filter { it.second <= best }
      .map { matchCase(word, it.first) }
      .distinctBy { it.lowercase() }
      .take(max)
  }

  // an identifier (fooBarBaz, MAX_VALUE) gets one suggestion: each misspelled part replaced by its best suggestion
  private fun suggestForParts(word: String, parts: List<IntRange>): String? {
    val result = StringBuilder(word)
    var changed = false
    for (part in parts.asReversed()) {
      val text = word.substring(part)
      if (isCorrect(text)) continue
      val replacement = suggest(text, 1).firstOrNull() ?: continue
      result.replace(part.first, part.last + 1, replacement)
      changed = true
    }
    return if (changed) result.toString() else null
  }

  private fun matchCase(original: String, suggestion: String): String = when {
    original.length > 1 && original.all { !it.isLetter() || it.isUpperCase() } -> suggestion.uppercase()
    original.first().isUpperCase() && suggestion.first().isLowerCase() -> suggestion.replaceFirstChar { it.uppercaseChar() }
    else -> suggestion
  }

  override fun reset() {
    adapter.reset()
    modificationTracker.incModificationCount()
  }

  override fun removeDictionary(name: String) {
    adapter.removeSource(name)
    modificationTracker.incModificationCount()
  }

  override fun getVariants(prefix: String): List<String> = emptyList()

  override fun removeDictionariesRecursively(directory: String) {
    val toRemove = adapter.names.filter { name ->
      FileUtil.isAncestor(directory, name, false) && isDictionaryLoad(name)
    }

    for (name in toRemove) {
      adapter.removeSource(name)
    }

    modificationTracker.incModificationCount()
  }

  @TestOnly
  fun dropSuggestionCache() {
    suggestionCache.invalidateAll()
    modificationTracker.incModificationCount()
  }
}

/** The words an identifier is made of: split at underscores, digits and other non-letters, and at camel humps */
internal fun identifierParts(word: String, acronymTakesNextCapital: Boolean = false): List<IntRange> {
  val result = ArrayList<IntRange>()
  var i = 0
  while (i < word.length) {
    if (!word[i].isLetter()) {
      i++
      continue
    }
    val start = i
    i++
    if (word[start].isUpperCase()) {
      // "HTTPServer": an upper-case run is a word of its own, its last letter starting the next word if a lower-case one follows
      while (i < word.length && word[i].isUpperCase()) i++
      if (i < word.length && word[i].isLowerCase() && i - start > 1) {
        if (acronymTakesNextCapital) {
          result.add(start until i)
          continue
        }
        i--
      }
    }
    while (i < word.length && (word[i].isLowerCase() || isApostropheInWord(word, i))) i++
    result.add(start until i)
  }
  return result
}

private val homoglyphs: Map<Char, Char> = mapOf(
  'А' to 'A', 'В' to 'B', 'Е' to 'E', 'К' to 'K', 'М' to 'M', 'Н' to 'H', 'О' to 'O', 'Р' to 'P', 'С' to 'C', 'Т' to 'T',
  'Х' to 'X', 'а' to 'a', 'е' to 'e', 'о' to 'o', 'р' to 'p', 'с' to 'c', 'у' to 'y', 'х' to 'x', 'і' to 'i', 'ј' to 'j',
  'ѕ' to 's', 'Α' to 'A', 'Β' to 'B', 'Ε' to 'E', 'Ζ' to 'Z', 'Η' to 'H', 'Ι' to 'I', 'Κ' to 'K', 'Μ' to 'M', 'Ν' to 'N',
  'Ο' to 'O', 'Ρ' to 'P', 'Τ' to 'T', 'Υ' to 'Y', 'Χ' to 'X', 'ο' to 'o',
)

internal fun isLatinHomoglyph(c: Char): Boolean = c in homoglyphs

/** Cyrillic and Greek letters that look like Latin ones, replaced by those in a word that is otherwise Latin */
internal fun homoglyphsToLatin(word: String): String {
  if (!Alphabet.ENGLISH.matchAny(word) || word.none { it in homoglyphs }) return word
  return String(CharArray(word.length) { homoglyphs[word[it]] ?: word[it] })
}

// "doesn't" is one word
private fun isApostropheInWord(word: String, i: Int): Boolean =
  (word[i] == '\'' || word[i] == '’') && i + 1 < word.length && word[i + 1].isLowerCase()

private data class SuggestionRequest(@JvmField val word: String, @JvmField val maxSuggestions: Int)
