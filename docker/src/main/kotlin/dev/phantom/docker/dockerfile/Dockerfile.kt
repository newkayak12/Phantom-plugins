// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.docker.dockerfile

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager

/**
 * A Dockerfile read line by line, as the builder reads it: parser directives, comments, `\` continuations (comment
 * and blank lines inside a continuation are skipped) and heredocs. Highlighting comes from the bundled TextMate
 * grammar; this model is what the checks work on.
 */
class Dockerfile private constructor(val instructions: List<Instruction>, val problems: List<Problem>) {
  /** One instruction: its keyword, and the argument text with continuation lines joined by spaces. */
  class Instruction(
    val keyword: String,
    val keywordRange: TextRange,
    val args: String,
    val argsRange: TextRange,
    /** Lines of heredoc bodies that belong to it, delimiter to body text. */
    val heredocs: List<String>,
    /** The first line started with whitespace and the instruction before did not end with a continuation. */
    val indentedAfterComplete: Boolean,
    val previous: Instruction?,
  ) {
    val upper: String = keyword.uppercase()
    val isExecForm: Boolean get() = args.trimStart().startsWith("[")

    /** Whitespace-separated tokens, keeping quoted parts together. */
    val tokens: List<String> by lazy { split(args) }
    val flags: List<String> get() = tokens.takeWhile { it.startsWith("--") }
    val operands: List<String> get() = tokens.dropWhile { it.startsWith("--") }
    fun flag(name: String): String? = flags.firstOrNull { it.startsWith("--$name=") }?.substringAfter('=')
  }

  /** A heredoc whose closing delimiter line never comes. */
  class Problem(val range: TextRange, val delimiter: String)

  companion object {
    val INSTRUCTIONS: Set<String> = setOf("FROM", "RUN", "CMD", "LABEL", "MAINTAINER", "EXPOSE", "ENV", "ADD", "COPY", "ENTRYPOINT",
                                          "VOLUME", "USER", "WORKDIR", "ARG", "ONBUILD", "STOPSIGNAL", "HEALTHCHECK", "SHELL")

    fun isDockerfile(name: String): Boolean =
      name == "Dockerfile" || name == "Containerfile" || name.startsWith("Dockerfile.") || name.startsWith("Containerfile.") ||
      name.endsWith(".Dockerfile") || name.endsWith(".dockerfile")

    fun of(file: PsiFile): Dockerfile? {
      if (!isDockerfile(file.name)) return null
      return CachedValuesManager.getCachedValue(file) { CachedValueProvider.Result.create(parse(file.text), file) }
    }

    private val HEREDOC = Regex("""<<(-?)(["']?)([A-Za-z_][A-Za-z0-9_]*)\2""")
    private val DIRECTIVE = Regex("""#\s*([a-zA-Z]+)\s*=\s*(\S+)\s*""")

    fun parse(text: String): Dockerfile {
      val lines = ArrayList<IntRange>() // [start, end) of each line without the newline
      var s = 0
      while (s <= text.length) {
        val e = text.indexOf('\n', s).let { if (it < 0) text.length else it }
        lines.add(s until e)
        if (e == text.length) break
        s = e + 1
      }
      fun line(i: Int) = text.substring(lines[i].first, lines[i].last + 1)

      var escape = '\\'
      var i = 0
      // parser directives: only at the very top, before any comment that isn't one
      while (i < lines.size) {
        val m = DIRECTIVE.matchEntire(line(i).trim()) ?: break
        if (m.groupValues[1].lowercase() == "escape") m.groupValues[2].firstOrNull()?.let { escape = it }
        i++
      }

      val instructions = ArrayList<Instruction>()
      val problems = ArrayList<Problem>()
      while (i < lines.size) {
        val raw = line(i)
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) { i++; continue }
        val lead = raw.length - raw.trimStart().length
        val start = lines[i].first + lead
        val keyword = trimmed.takeWhile { !it.isWhitespace() }
        val keywordRange = TextRange(start, start + keyword.length)
        val parts = ArrayList<String>()
        var argsStart = start + keyword.length
        var argsEnd = lines[i].last + 1
        var current = raw.substring(lead + keyword.length)
        var continued: Boolean
        while (true) {
          val body = current.trimEnd()
          continued = body.endsWith(escape)
          parts.add(if (continued) body.dropLast(1) else body)
          argsEnd = lines[i].first + raw.trimEnd().length.coerceAtLeast(0)
          if (!continued) break
          // the next line that is neither blank nor a comment continues the instruction
          i++
          while (i < lines.size && (line(i).isBlank() || line(i).trimStart().startsWith("#"))) i++
          if (i >= lines.size) break
          current = line(i)
          argsEnd = lines[i].last + 1
        }
        val args = parts.joinToString(" ") { it.trim() }.trim()
        argsStart = minOf(argsStart, argsEnd)
        // heredocs: their bodies follow the instruction's last line
        val heredocs = ArrayList<String>()
        if (keyword.uppercase() in setOf("RUN", "COPY", "ADD")) {
          for (m in HEREDOC.findAll(args)) {
            val delimiter = m.groupValues[3]
            val stripTabs = m.groupValues[1] == "-"
            var j = i + 1
            val body = StringBuilder()
            var closed = false
            while (j < lines.size) {
              val l = line(j)
              if ((if (stripTabs) l.trimStart('\t') else l) == delimiter) { closed = true; break }
              body.append(l).append('\n')
              j++
            }
            if (!closed) problems.add(Problem(keywordRange, delimiter))
            heredocs.add(body.toString())
            i = j
          }
        }
        instructions.add(Instruction(keyword, keywordRange, args, TextRange(argsStart, argsEnd), heredocs,
                                     indentedAfterComplete = lead > 0 && instructions.isNotEmpty(),
                                     previous = instructions.lastOrNull()))
        i++
      }
      return Dockerfile(instructions, problems)
    }

    /** Splits on whitespace outside quotes. */
    fun split(args: String): List<String> {
      val out = ArrayList<String>()
      val sb = StringBuilder()
      var quote: Char? = null
      for (c in args) {
        when {
          quote != null -> { sb.append(c); if (c == quote) quote = null }
          c == '"' || c == '\'' -> { sb.append(c); quote = c }
          c.isWhitespace() -> if (sb.isNotEmpty()) { out.add(sb.toString()); sb.clear() }
          else -> sb.append(c)
        }
      }
      if (sb.isNotEmpty()) out.add(sb.toString())
      return out
    }

    /** The strings of a JSON array of strings, or null when [text] is not one (single quotes are not JSON). */
    fun jsonArray(text: String): List<String>? {
      val t = text.trim()
      if (!t.startsWith("[") || !t.endsWith("]")) return null
      val out = ArrayList<String>()
      var k = 1
      fun skip() { while (k < t.length && t[k].isWhitespace()) k++ }
      skip()
      if (k < t.length && t[k] == ']') return if (k == t.length - 1) out else null
      while (k < t.length) {
        skip()
        if (k >= t.length || t[k] != '"') return null
        val sb = StringBuilder()
        k++
        while (k < t.length && t[k] != '"') {
          if (t[k] == '\\' && k + 1 < t.length) { sb.append(t[k + 1]); k += 2 } else sb.append(t[k++])
        }
        if (k >= t.length) return null
        k++
        out.add(sb.toString())
        skip()
        if (k < t.length && t[k] == ',') { k++; continue }
        if (k < t.length && t[k] == ']' && k == t.length - 1) return out
        return null
      }
      return null
    }
  }
}
