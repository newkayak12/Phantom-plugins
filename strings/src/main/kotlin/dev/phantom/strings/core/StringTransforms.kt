// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.strings.core

import com.intellij.openapi.util.text.NaturalComparator
import com.intellij.openapi.util.text.StringUtil
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.net.URLEncoder
import java.util.Base64
import kotlin.random.Random

/** Pure text transforms; the actions apply them to each selection, or to the word at the caret. */
object StringTransforms {
  /** Splits `HTTPServer2_fooBar-baz.qux` into HTTP, Server2, foo, Bar, baz, qux. */
  fun words(text: String): List<String> {
    val out = ArrayList<String>()
    val cur = StringBuilder()
    for (i in text.indices) {
      val c = text[i]
      if (!c.isLetterOrDigit()) {
        if (cur.isNotEmpty()) { out += cur.toString(); cur.clear() }
        continue
      }
      if (cur.isNotEmpty() && c.isUpperCase()) {
        val prev = text[i - 1]
        val next = text.getOrNull(i + 1)
        // fooBar, foo2Bar | HTTPServer: the S starts a word, the P does not
        if (prev.isLowerCase() || prev.isDigit() || (prev.isUpperCase() && next != null && next.isLowerCase())) {
          out += cur.toString(); cur.clear()
        }
      }
      cur.append(c)
    }
    if (cur.isNotEmpty()) out += cur.toString()
    return out
  }

  fun camel(text: String): String = affixed(text) { words(it).mapIndexed { i, w -> if (i == 0) w.lowercase() else capitalized(w) }.joinToString("") }
  fun pascal(text: String): String = affixed(text) { words(it).joinToString("") { w -> capitalized(w) } }
  fun snake(text: String): String = affixed(text) { words(it).joinToString("_") { w -> w.lowercase() } }
  fun screamingSnake(text: String): String = affixed(text) { words(it).joinToString("_") { w -> w.uppercase() } }
  fun kebab(text: String): String = affixed(text) { words(it).joinToString("-") { w -> w.lowercase() } }
  fun dot(text: String): String = affixed(text) { words(it).joinToString(".") { w -> w.lowercase() } }
  fun spaced(text: String): String = affixed(text) { words(it).joinToString(" ") { w -> w.lowercase() } }

  private fun capitalized(word: String) = word.lowercase().replaceFirstChar { it.uppercaseChar() }

  /** Applies [f] between the first and the last letter or digit, so `_private`, `__init__` and `$name` keep their marks. */
  private inline fun affixed(text: String, f: (String) -> String): String {
    val start = text.indexOfFirst { it.isLetterOrDigit() }
    if (start < 0) return text
    val end = text.indexOfLast { it.isLetterOrDigit() } + 1
    return text.substring(0, start) + f(text.substring(start, end)) + text.substring(end)
  }

  private val cycle: List<(String) -> String> = listOf(::camel, ::snake, ::screamingSnake, ::kebab, ::pascal)

  /** camelCase → snake_case → SCREAMING_SNAKE → kebab-case → PascalCase → camelCase, skipping styles that change nothing. */
  fun cycleCase(text: String): String = affixed(text) { cycleCore(it) }

  private fun cycleCore(text: String): String {
    val start = when {
      text.contains('_') && text.none { it.isLowerCase() } -> 2
      text.contains('_') -> 1
      text.contains('-') -> 3
      text.firstOrNull()?.isUpperCase() == true -> 4
      else -> 0
    }
    for (step in 1..cycle.size) {
      val result = cycle[(start + step) % cycle.size](text)
      if (result != text) return result
    }
    return text
  }

  fun escapeJava(text: String): String = StringUtil.escapeStringCharacters(text)
  fun unescapeJava(text: String): String = StringUtil.unescapeStringCharacters(text)
  fun escapeHtml(text: String): String = StringUtil.escapeXmlEntities(text)
  /** The five XML entities and numeric character references (`&#39;`, `&#x27;`), in one pass so `&amp;lt;` gives `&lt;`. */
  fun unescapeHtml(text: String): String = entity.replace(text) { m ->
    val (dec, hex, name) = m.destructured
    when {
      name.isNotEmpty() -> namedEntities.getValue(name)
      else -> {
        val cp = if (dec.isNotEmpty()) dec.toInt() else hex.toInt(16)
        if (Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else m.value
      }
    }
  }
  private val entity = Regex("&(?:#([0-9]{1,7})|#[xX]([0-9a-fA-F]{1,6})|(lt|gt|amp|quot|apos));")
  private val namedEntities = mapOf("lt" to "<", "gt" to ">", "amp" to "&", "quot" to "\"", "apos" to "'")

  fun encodeUrl(text: String): String = URLEncoder.encode(text, Charsets.UTF_8)
  fun decodeUrl(text: String): String = URLDecoder.decode(text, Charsets.UTF_8)
  fun encodeBase64(text: String): String = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

  /** Throws IllegalArgumentException for input that is not Base64 or does not decode to UTF-8 text. */
  fun decodeBase64(text: String): String {
    val bytes = Base64.getMimeDecoder().decode(text.trim())
    return try {
      Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    }
    catch (e: CharacterCodingException) {
      throw IllegalArgumentException(e)
    }
  }

  fun sortLines(text: String): String = lines(text) { it.sortedWith(NaturalComparator.INSTANCE) }
  fun sortLinesDescending(text: String): String = lines(text) { it.sortedWith(NaturalComparator.INSTANCE.reversed()) }
  fun reverseLines(text: String): String = lines(text) { it.reversed() }
  fun shuffleLines(text: String, random: Random = Random.Default): String = lines(text) { it.shuffled(random) }
  fun removeDuplicateLines(text: String): String = lines(text) { it.distinct() }
  fun removeBlankLines(text: String): String = lines(text) { l -> l.filter { it.isNotBlank() } }
  fun trimLines(text: String): String = lines(text) { l -> l.map { it.trim() } }

  /** Applies [f] to the lines of [text]; a trailing line break stays where it was. */
  private fun lines(text: String, f: (List<String>) -> List<String>): String {
    val trailing = text.endsWith("\n")
    val body = if (trailing) text.dropLast(1) else text
    val result = f(body.split("\n")).joinToString("\n")
    return if (trailing) result + "\n" else result
  }
}
