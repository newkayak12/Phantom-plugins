// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.grazie.nlp

import com.intellij.openapi.progress.ProgressManager

/**
 * Tells the language of a text, simply: first by script (Hangul is Korean, kana is Japanese, Greek letters are Greek, ...),
 * then, among the languages sharing a script (Latin, Cyrillic, Arabic), by the most frequent short function words of
 * each language and the letters only some of them use. It needs no models: good enough to tell which LanguageTool
 * language to check a comment or a commit message with, and to leave texts in other languages alone.
 */
object LanguageDetector {
  /** Texts are detected by their first [LIMIT] characters */
  const val LIMIT: Int = 1_000

  class Result(
    /** The detected language, or [Language.UNKNOWN] */
    val preferred: Language,
    /** Whether the text gave enough evidence to be sure (used to suggest enabling a language) */
    val isReliable: Boolean,
    /** Whether anything in the text points to the language (function words, letters), rather than it being a default */
    val hasEvidence: Boolean = isReliable,
  ) {
    override fun toString(): String = "$preferred${if (isReliable) "" else "?"}"
  }

  private val unknown = Result(Language.UNKNOWN, false)

  fun detect(text: CharSequence): Result {
    val sample = if (text.length > LIMIT) text.subSequence(0, LIMIT) else text
    val scripts = HashMap<Character.UnicodeScript, Int>()
    var kana = 0
    for (c in sample) {
      if (!c.isLetter()) continue
      val script = Character.UnicodeScript.of(c.code)
      if (Alphabet.isKana(c)) kana++
      scripts.merge(script, 1, Int::plus)
    }
    val letters = scripts.values.sum()
    if (letters == 0) return unknown

    val (script, count) = scripts.maxBy { it.value }
    val reliableBySize = count >= 10
    return when (script) {
      Character.UnicodeScript.HANGUL -> Result(Language.KOREAN, reliableBySize, true)
      Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> Result(Language.JAPANESE, reliableBySize, true)
      Character.UnicodeScript.HAN -> Result(if (kana * 10 >= count) Language.JAPANESE else Language.CHINESE, reliableBySize, true)
      Character.UnicodeScript.GREEK -> Result(Language.GREEK, reliableBySize, true)
      Character.UnicodeScript.KHMER -> Result(Language.KHMER, reliableBySize, true)
      Character.UnicodeScript.TAMIL -> Result(Language.TAMIL, reliableBySize, true)
      Character.UnicodeScript.ARABIC -> {
        val persian = sample.any { it in persianLetters }
        Result(if (persian) Language.PERSIAN else Language.ARABIC, reliableBySize, true)
      }
      Character.UnicodeScript.CYRILLIC -> byWords(sample, cyrillic, Language.RUSSIAN)
      Character.UnicodeScript.LATIN -> byWords(sample, latin, defaultLatin(sample))
      else -> unknown
    }
  }

  // Without any function word, mostly English letters read as English: "Fix NPE in parser" is a fine commit message
  private fun defaultLatin(text: CharSequence): Language {
    var ascii = 0
    var all = 0
    for (c in text) {
      if (!c.isLetter()) continue
      all++
      if (c < '\u0080') ascii++
    }
    return if (ascii * 10 >= all * 9) Language.ENGLISH else Language.UNKNOWN
  }

  private fun byWords(text: CharSequence, profiles: List<Profile>, fallback: Language): Result {
    val words = Words.of(text).map { it.lowercase() }
    if (words.isEmpty()) return Result(fallback, false, false)

    val scores = HashMap<Language, Double>()
    for (profile in profiles) {
      ProgressManager.checkCanceled()
      var score = 0.0
      for (word in words) {
        if (word in profile.words) score += 1.0
        if (word.any { it in profile.letters }) score += 0.5
      }
      if (score > 0) scores[profile.language] = score
    }
    if (scores.isEmpty()) return Result(fallback, false, false)

    // ties go to the language listed first (English among the Latin ones)
    val ranked = profiles.map { it.language }.filter { it in scores }.sortedByDescending { scores[it] }
    val best = ranked.first()
    val bestScore = scores.getValue(best)
    val second = ranked.getOrNull(1)?.let { scores.getValue(it) } ?: 0.0
    val reliable = words.size >= 4 && bestScore >= 2 && bestScore >= 1.5 * second && bestScore - second >= 1
    return Result(best, reliable, true)
  }

  private class Profile(val language: Language, words: String, letters: String = "") {
    val words: Set<String> = words.split(' ').filter { it.isNotEmpty() }.toHashSet()
    val letters: Set<Char> = letters.toHashSet()
  }

  private val persianLetters = setOf('پ', 'چ', 'ژ', 'گ', 'ک', 'ی')

  // The most frequent short words of each language, and letters of its alphabet that few others of the list share
  private val latin = listOf(
    Profile(Language.ENGLISH, "a i the and of to is in that it for with as was on are be this by not or have from at which but " +
                              "an they you we can will if when has were their there should would been does do what its"),
    Profile(Language.GERMAN, "der die das und ist nicht ein eine zu den von mit sich des auf für im dem auch es wird bei " +
                             "oder aus wenn nach wie sind werden kann noch nur ich wir sie", "äöüß"),
    Profile(Language.FRENCH, "le la les et des est un une du en que qui dans pour pas sur au avec ce il elle sont par plus " +
                             "ne se ou nous vous mais être cette leur", "éèêàùçœ"),
    Profile(Language.SPANISH, "el la los las y de que en un una es por con para del se no al lo como más pero su sus le ya " +
                              "o este esta está son muy también", "ñ¿¡áéíóú"),
    Profile(Language.PORTUGUESE, "o a os as e de que em um uma é do da dos das para com não por se na no mais como mas ao " +
                                 "ou ser foi está são também", "ãõçâêô"),
    Profile(Language.ITALIAN, "il lo la gli le e di che è un una per non con del della dei si da sono ma come anche più " +
                              "questo questa nel alla essere", "àèìòù"),
    Profile(Language.DUTCH, "de het een en van is dat niet te in op voor met zijn er aan ook als bij of maar om nog wordt " +
                            "worden deze die hij ze ik wij", "ĳ"),
    Profile(Language.POLISH, "i w nie na z się że to do jest jak o co ale po tak za od przez dla jego być są czy tylko " +
                             "może także oraz", "ąćęłńóśźż"),
    Profile(Language.CATALAN, "el la els les i de que en un una és per amb no del al es com més però seu seva són també " +
                              "aquest aquesta", "àèéíòóúç·"),
    Profile(Language.DANISH, "og i at det er en til på som de med for ikke der af den har var et kan om vil skal fra men " +
                             "også jeg", "æøå"),
    Profile(Language.SWEDISH, "och i att det är en som på av för med till den inte har de ett om var men kan så också " +
                              "från jag vi ska", "åäö"),
    Profile(Language.ROMANIAN, "și în de la cu nu că o un este pe mai pentru din care se sunt ca dar sau fost acest această",
            "ăâîșțşţ"),
    Profile(Language.SLOVAK, "a v na je sa že to s z do ako ale aj o by som pre čo len tak od už sú bol bola", "ľĺŕäôčďňšťž"),
    Profile(Language.SLOVENIAN, "in je v na da se za z ki so ne pa tudi kot bi do ali po od sem smo bil bila", "čšž"),
    Profile(Language.ESPERANTO, "la kaj de en estas al ne por kun ke mi vi li ŝi ni ili sed tiu tio ĉi pli", "ĉĝĥĵŝŭ"),
    Profile(Language.GALICIAN, "o a os as e de que en un unha é do da dos das para con non por se na no máis como pero " +
                               "tamén ao", "ñ"),
    Profile(Language.ASTURIAN, "el la los les y de que en un una ye pol con pa nun nel al lo como más pero tamién", "ḥḷ"),
    Profile(Language.BRETON, "ar an al ha e eo da ez gant war evit hag ur un met pe zo ne ket emañ", "ñ"),
    Profile(Language.IRISH, "na agus ar le ag go sé sí ní do bhí atá mar ach ó leis seo sin", "áéíóú"),
    Profile(Language.TAGALOG, "ang ng sa na at mga ay si ni ko mo siya ako ito iyon para kung hindi rin din lang"),
  )

  private val cyrillic = listOf(
    Profile(Language.RUSSIAN, "и в не на что я с он как а то это все она так его но да ты к у же вы за бы по от из мы или " +
                              "для который если", "ыэъё"),
    Profile(Language.UKRAINIAN, "і в не на що я з він як а то це все вона так його але та ти до у вже ви за б по від ми або " +
                                "для який якщо", "їєґі"),
    Profile(Language.BELARUSIAN, "і ў не на што я з ён як а гэта усё яна так яго але ты да у ужо вы за б па ад мы або для " +
                                 "які калі", "ўі"),
  )
}
