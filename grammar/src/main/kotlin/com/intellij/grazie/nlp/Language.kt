// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.grazie.nlp

/** ISO 639 codes of the languages Phantom Grammar knows about. The names are used in rule ids ("LanguageTool.EN.") */
enum class LanguageISO {
  EN, AR, AST, BE, BR, CA, DA, DE, EL, EO, ES, FA, FR, GA, GL, IT, JA, KM, KO, NL, PL, PT, RO, RU, SK, SL, SV, TA, TL, UK, ZH;

  companion object {
    @JvmStatic
    fun parse(code: String): LanguageISO? {
      val base = code.substringBefore('_').substringBefore('-')
      return entries.find { it.name.equals(base, ignoreCase = true) }
    }
  }
}

/**
 * A natural language, as the language detector reports it. [UNKNOWN] when it can't tell. Languages that LanguageTool
 * can't check (Korean) are still detected, so that their texts are left alone instead of being checked as English.
 */
enum class Language(val iso: LanguageISO?, val englishName: String, val nativeName: String, val alphabet: Alphabet) {
  ENGLISH(LanguageISO.EN, "English", "English", Alphabet.LATIN),
  ARABIC(LanguageISO.AR, "Arabic", "العربية", Alphabet.ARABIC),
  ASTURIAN(LanguageISO.AST, "Asturian", "Asturianu", Alphabet.LATIN),
  BELARUSIAN(LanguageISO.BE, "Belarusian", "Беларуская", Alphabet.CYRILLIC),
  BRETON(LanguageISO.BR, "Breton", "Brezhoneg", Alphabet.LATIN),
  CATALAN(LanguageISO.CA, "Catalan", "Català", Alphabet.LATIN),
  DANISH(LanguageISO.DA, "Danish", "Dansk", Alphabet.LATIN),
  GERMAN(LanguageISO.DE, "German", "Deutsch", Alphabet.LATIN),
  GREEK(LanguageISO.EL, "Greek", "Ελληνικά", Alphabet.GREEK),
  ESPERANTO(LanguageISO.EO, "Esperanto", "Esperanto", Alphabet.LATIN),
  SPANISH(LanguageISO.ES, "Spanish", "Español", Alphabet.LATIN),
  PERSIAN(LanguageISO.FA, "Persian", "فارسی", Alphabet.ARABIC),
  FRENCH(LanguageISO.FR, "French", "Français", Alphabet.LATIN),
  IRISH(LanguageISO.GA, "Irish", "Gaeilge", Alphabet.LATIN),
  GALICIAN(LanguageISO.GL, "Galician", "Galego", Alphabet.LATIN),
  ITALIAN(LanguageISO.IT, "Italian", "Italiano", Alphabet.LATIN),
  JAPANESE(LanguageISO.JA, "Japanese", "日本語", Alphabet.JAPANESE),
  KHMER(LanguageISO.KM, "Khmer", "ភាសាខ្មែរ", Alphabet.KHMER),
  KOREAN(LanguageISO.KO, "Korean", "한국어", Alphabet.HANGUL),
  DUTCH(LanguageISO.NL, "Dutch", "Nederlands", Alphabet.LATIN),
  POLISH(LanguageISO.PL, "Polish", "Polski", Alphabet.LATIN),
  PORTUGUESE(LanguageISO.PT, "Portuguese", "Português", Alphabet.LATIN),
  ROMANIAN(LanguageISO.RO, "Romanian", "Română", Alphabet.LATIN),
  RUSSIAN(LanguageISO.RU, "Russian", "Русский", Alphabet.CYRILLIC),
  SLOVAK(LanguageISO.SK, "Slovak", "Slovenčina", Alphabet.LATIN),
  SLOVENIAN(LanguageISO.SL, "Slovenian", "Slovenščina", Alphabet.LATIN),
  SWEDISH(LanguageISO.SV, "Swedish", "Svenska", Alphabet.LATIN),
  TAMIL(LanguageISO.TA, "Tamil", "தமிழ்", Alphabet.TAMIL),
  TAGALOG(LanguageISO.TL, "Tagalog", "Tagalog", Alphabet.LATIN),
  UKRAINIAN(LanguageISO.UK, "Ukrainian", "Українська", Alphabet.CYRILLIC),
  CHINESE(LanguageISO.ZH, "Chinese", "中文", Alphabet.HAN),
  UNKNOWN(null, "Unknown", "Unknown", Alphabet.LATIN);

  /** Whether words of this language are separated by spaces (not so in Chinese, Japanese and Khmer) */
  val hasWhitespaces: Boolean
    get() = !alphabet.isAsian

  companion object {
    @JvmStatic
    val all: List<Language> = entries.filter { it != UNKNOWN }
  }
}

/** The scripts the language detector and the spellchecker tell apart, by the letters of a word */
enum class Alphabet(val isAsian: Boolean = false) {
  /** The 26 letters of the English alphabet */
  ENGLISH {
    override fun isLetter(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z'
  },
  LATIN {
    override fun isLetter(c: Char): Boolean = c.isLetter() && Character.UnicodeScript.of(c.code) == Character.UnicodeScript.LATIN
  },
  CYRILLIC {
    override fun isLetter(c: Char): Boolean = c.isLetter() && Character.UnicodeScript.of(c.code) == Character.UnicodeScript.CYRILLIC
  },
  GREEK {
    override fun isLetter(c: Char): Boolean = c.isLetter() && Character.UnicodeScript.of(c.code) == Character.UnicodeScript.GREEK
  },
  ARABIC {
    override fun isLetter(c: Char): Boolean = c.isLetter() && Character.UnicodeScript.of(c.code) == Character.UnicodeScript.ARABIC
  },
  HAN(isAsian = true) {
    override fun isLetter(c: Char): Boolean = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN
  },
  /** Kana and the kanji (Han) they are written with */
  JAPANESE(isAsian = true) {
    override fun isLetter(c: Char): Boolean = isKana(c) || HAN.isLetter(c)
  },
  HANGUL {
    override fun isLetter(c: Char): Boolean = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HANGUL
  },
  KHMER(isAsian = true) {
    override fun isLetter(c: Char): Boolean = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.KHMER
  },
  TAMIL {
    override fun isLetter(c: Char): Boolean = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.TAMIL
  };

  abstract fun isLetter(c: Char): Boolean

  /** Whether any letter of the text belongs to this alphabet */
  fun matchAny(text: CharSequence): Boolean = text.any { isLetter(it) }

  /** Whether the text has letters and all of them belong to this alphabet (other characters don't count) */
  fun matchEntire(text: CharSequence): Boolean {
    var hasLetters = false
    for (c in text) {
      if (!c.isLetter()) continue
      if (!isLetter(c)) return false
      hasLetters = true
    }
    return hasLetters
  }

  companion object {
    internal fun isKana(c: Char): Boolean {
      val script = Character.UnicodeScript.of(c.code)
      return script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA
    }
  }
}
