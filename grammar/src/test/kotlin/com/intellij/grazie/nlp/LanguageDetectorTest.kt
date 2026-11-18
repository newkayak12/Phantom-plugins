package com.intellij.grazie.nlp

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class LanguageDetectorTest : BasePlatformTestCase() {
  private fun assertDetected(expected: Language, text: String) {
    assertEquals(text, expected, LanguageDetector.detect(text).preferred)
  }

  fun `test languages`() {
    assertDetected(Language.ENGLISH, "Fix the crash when the user opens an empty project")
    assertDetected(Language.ENGLISH, "Fix NPE in parser")
    assertDetected(Language.ENGLISH, "use concurrent pinging within PF instance, using a worker pool")
    assertDetected(Language.GERMAN, "Das ist ein Beispiel, und es wird nicht mehr gebraucht.")
    assertDetected(Language.FRENCH, "Le fichier est vide et il ne contient pas de données.")
    assertDetected(Language.SPANISH, "El archivo está vacío y no se puede abrir con este programa.")
    assertDetected(Language.PORTUGUESE, "O arquivo não foi encontrado e também não está na pasta.")
    assertDetected(Language.RUSSIAN, "Это пример текста, который не нужно проверять.")
    assertDetected(Language.UKRAINIAN, "Це приклад тексту, який не потрібно перевіряти.")
    assertDetected(Language.KOREAN, "이 함수는 캐시에 있는 항목의 개수를 반환합니다.")
    assertDetected(Language.JAPANESE, "この関数はキャッシュの項目数を返します。")
    assertDetected(Language.CHINESE, "这个函数返回缓存中的项目数量。")
    assertDetected(Language.GREEK, "Αυτό είναι ένα παράδειγμα.")
    assertDetected(Language.UNKNOWN, "12345 + 678")
  }

  fun `test reliability`() {
    assertTrue(LanguageDetector.detect("Das ist ein Beispiel, und es wird nicht mehr gebraucht.").isReliable)
    assertFalse(LanguageDetector.detect("Fix NPE in parser").isReliable)
  }

  fun `test commit prefixes`() {
    assertEquals("Fix the crash", "IDEA-1234 Fix the crash".substring(CommitPrefixStripper.stripPrefix("IDEA-1234 Fix the crash")))
    assertEquals("add a check", "fix(parser): add a check".substring(CommitPrefixStripper.stripPrefix("fix(parser): add a check")))
    assertEquals("Fix the crash", "[ABC-12] Fix the crash".substring(CommitPrefixStripper.stripPrefix("[ABC-12] Fix the crash")))
    assertEquals(0, CommitPrefixStripper.stripPrefix("Fix the crash"))
  }

  fun `test sentence-level code detection`() {
    assertTrue(CodeDetector.findCodeFragments("call foo.bar() first").isNotEmpty())
    assertTrue(CodeDetector.findCodeFragments("int x = y + 1;").isNotEmpty())
    assertTrue(CodeDetector.findCodeFragments("This is a plain sentence.").isEmpty())
  }

  fun `test edit distance`() {
    assertEquals(0, Levenshtein.distance("banana", "banana"))
    assertEquals(1, Levenshtein.distance("bannana", "banana"))
    assertEquals(3, Levenshtein.distance("kitten", "sitting"))
    assertEquals(2, Levenshtein.distance("kitten", "sitting", 2))
  }
}
