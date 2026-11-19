// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springboot.core

sealed interface ConfigContext

/** The caret is on a key. [parentPath] comes from the enclosing structure, [typed] is what is on the line so far. */
class KeyContext(val parentPath: String, val typed: String, val lineIndent: Int, val indentUnit: Int) : ConfigContext {
  val fullTyped: String get() = ConfigNames.join(parentPath, typed)

  /** The path whose children are offered, and the part of the last segment typed so far. */
  val groupPath: String get() = fullTyped.substringBeforeLast('.', "").let { if (typed.contains('.')) it else parentPath }
  val segmentPrefix: String get() = typed.substringAfterLast('.')
}

/** The caret is in the value of [key]. */
class ValueContext(val key: String, val typed: String) : ConfigContext

/**
 * Reads the configuration key structure from the document text around the caret instead of the PSI tree,
 * so it also works on the half-typed, often unparsable line being completed.
 */
interface ConfigSyntax {
  fun contextAt(text: CharSequence, offset: Int): ConfigContext?

  /** Full key name of the key under [offset], or null when the offset is not on a key. */
  fun keyAt(text: CharSequence, offset: Int): String?
}

internal fun lineStartOf(text: CharSequence, offset: Int): Int {
  var i = offset.coerceAtMost(text.length)
  while (i > 0 && text[i - 1] != '\n') i--
  return i
}

internal fun lineEndOf(text: CharSequence, offset: Int): Int {
  var i = offset
  while (i < text.length && text[i] != '\n') i++
  return i
}

private fun indentOf(line: CharSequence): Int {
  var i = 0
  while (i < line.length && line[i] == ' ') i++
  return i
}

private fun String.unquote(): String =
  if (length >= 2 && (startsWith('"') && endsWith('"') || startsWith('\'') && endsWith('\''))) substring(1, length - 1) else this

private val KEY_CHARS: (Char) -> Boolean = { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' || it == '[' || it == ']' }

object YamlConfigSyntax : ConfigSyntax {
  /** `key:` with nothing after it, which opens a nested mapping. */
  private val PARENT_KEY = Regex("""([^\s:#'"][^:#]*|'[^']*'|"[^"]*"):(\s+#.*)?""")

  override fun contextAt(text: CharSequence, offset: Int): ConfigContext? {
    val lineStart = lineStartOf(text, offset)
    val linePrefix = text.subSequence(lineStart, offset).toString()
    val indent = indentOf(linePrefix)
    val rest = linePrefix.substring(indent)
    if (rest.startsWith("#") || rest.startsWith("- ") || rest.startsWith("---")) return null
    val parents = parentKeys(text, lineStart, indent) ?: return null
    val colon = rest.indexOf(": ")
    if (colon >= 0) {
      val value = rest.substring(colon + 2).trimStart()
      if (value.contains(" #")) return null
      return ValueContext(ConfigNames.join(parents, rest.substring(0, colon).trim().unquote()), value.trimStart('"', '\''))
    }
    if (!rest.all(KEY_CHARS)) return null
    return KeyContext(parents, rest, indent, indentUnit(text))
  }

  override fun keyAt(text: CharSequence, offset: Int): String? {
    val lineStart = lineStartOf(text, offset)
    val line = text.subSequence(lineStart, lineEndOf(text, offset)).toString()
    val indent = indentOf(line)
    val colon = line.indexOf(':', indent)
    if (colon < 0 || offset - lineStart < indent || offset - lineStart > colon) return null
    val key = line.substring(indent, colon).trim().unquote()
    if (key.isEmpty() || key.startsWith("#") || key.startsWith("-")) return null
    val parents = parentKeys(text, lineStart, indent) ?: return null
    return ConfigNames.join(parents, key)
  }

  /** Keys of the enclosing mappings, outermost first; null inside lists, which v0.1 does not model. */
  private fun parentKeys(text: CharSequence, lineStart: Int, indent: Int): String? {
    val keys = ArrayList<String>()
    var current = indent
    var end = lineStart - 1
    while (end >= 0 && current > 0) {
      val start = lineStartOf(text, end)
      val line = text.subSequence(start, end).toString()
      end = start - 1
      val trimmed = line.trim()
      if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
      if (line.startsWith("---")) break
      val lineIndent = indentOf(line)
      if (lineIndent >= current) continue
      val match = PARENT_KEY.matchEntire(trimmed) ?: return null
      keys.add(match.groupValues[1].trim().unquote())
      current = lineIndent
    }
    return keys.asReversed().joinToString(".")
  }

  /** The smallest indentation used in the file, so inserted nested keys follow the file's style. */
  private fun indentUnit(text: CharSequence): Int {
    var unit = Int.MAX_VALUE
    var i = 0
    var scanned = 0
    while (i < text.length && scanned < 400) {
      val end = lineEndOf(text, i)
      val n = indentOf(text.subSequence(i, end))
      if (n in 1 until unit && n < end - i) unit = n
      i = end + 1
      scanned++
    }
    return if (unit == Int.MAX_VALUE || unit > 8) 2 else unit
  }
}

object PropertiesConfigSyntax : ConfigSyntax {
  override fun contextAt(text: CharSequence, offset: Int): ConfigContext? {
    val lineStart = lineStartOf(text, offset)
    if (lineStart > 1 && text[lineStart - 2] == '\\') return null // continuation line
    val linePrefix = text.subSequence(lineStart, offset).toString().trimStart()
    if (linePrefix.startsWith("#") || linePrefix.startsWith("!")) return null
    val separator = linePrefix.indexOfFirst { it == '=' || it == ':' }
    if (separator >= 0) {
      return ValueContext(linePrefix.substring(0, separator).trim(), linePrefix.substring(separator + 1).trimStart())
    }
    if (!linePrefix.all(KEY_CHARS)) return null
    return KeyContext("", linePrefix, 0, 0)
  }

  override fun keyAt(text: CharSequence, offset: Int): String? {
    val lineStart = lineStartOf(text, offset)
    val line = text.subSequence(lineStart, lineEndOf(text, offset)).toString()
    val indent = indentOf(line)
    val separator = line.indexOfFirst { it == '=' || it == ':' }.let { if (it < 0) line.length else it }
    if (offset - lineStart < indent || offset - lineStart > separator) return null
    return line.substring(indent, separator).trim().takeIf { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") }
  }
}
