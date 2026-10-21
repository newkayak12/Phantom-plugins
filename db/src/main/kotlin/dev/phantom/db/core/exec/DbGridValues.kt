// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import java.math.BigDecimal
import java.math.MathContext

/** What the value viewer shows for a cell: its kind and the text for it (pretty-printed JSON, a hex dump of bytes). */
class DbCellView(val kind: Kind, val text: String) {
  enum class Kind { NULL, TEXT, JSON, XML, BINARY, IMAGE }

  companion object {
    /** Bytes shown in a hex dump; the rest can be saved to a file. */
    const val HEX_LIMIT: Int = 64 * 1024

    fun of(value: Any?): DbCellView = when (value) {
      null -> DbCellView(Kind.NULL, "")
      is ByteArray -> DbCellView(if (isImage(value)) Kind.IMAGE else Kind.BINARY, hex(value))
      else -> {
        val s = value.toString()
        val trimmed = s.trim()
        when {
          (trimmed.startsWith("{") || trimmed.startsWith("[")) -> prettyJson(trimmed)?.let { DbCellView(Kind.JSON, it) } ?: DbCellView(Kind.TEXT, s)
          trimmed.startsWith("<") && trimmed.endsWith(">") -> DbCellView(Kind.XML, s)
          else -> DbCellView(Kind.TEXT, s)
        }
      }
    }

    /** PNG or JPEG, by their signatures. */
    fun isImage(bytes: ByteArray): Boolean =
      bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() ||
      bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()

    /** `00000000  48 65 6c 6c 6f ...  |Hello...|`, 16 bytes a line, at most [HEX_LIMIT] bytes. */
    fun hex(bytes: ByteArray, limit: Int = HEX_LIMIT): String = buildString {
      val shown = minOf(bytes.size, limit)
      for (line in 0 until shown step 16) {
        append("%08x ".format(line))
        for (i in line until line + 16) {
          if (i == line + 8) append(' ')
          if (i < shown) append(" %02x".format(bytes[i])) else append("   ")
        }
        append("  |")
        for (i in line until minOf(line + 16, shown)) {
          val c = bytes[i].toInt() and 0xFF
          append(if (c in 0x20..0x7E) c.toChar() else '.')
        }
        append("|\n")
      }
      if (bytes.size > shown) append("… ${bytes.size - shown} more bytes\n")
    }

    /**
     * [json] re-indented with two spaces, or null when it is not one well-formed JSON object or array (checked by
     * brackets and strings only; values are kept as written).
     */
    fun prettyJson(json: String): String? {
      val out = StringBuilder()
      val stack = ArrayDeque<Char>()
      var i = 0
      var done = false
      fun newline() {
        out.append('\n')
        repeat(stack.size) { out.append("  ") }
      }
      while (i < json.length) {
        val c = json[i]
        if (done && !c.isWhitespace()) return null
        when {
          c == '"' -> {
            val start = i++
            while (i < json.length && json[i] != '"') i += if (json[i] == '\\') 2 else 1
            if (i >= json.length) return null
            out.append(json, start, i + 1)
          }
          c == '{' || c == '[' -> {
            val close = if (c == '{') '}' else ']'
            val next = json.indexOfFirst(i + 1) { !it.isWhitespace() }
            if (next >= 0 && json[next] == close) {
              out.append(c).append(close)
              i = next
              if (stack.isEmpty()) done = true
            }
            else {
              out.append(c)
              stack.addLast(close)
              newline()
            }
          }
          c == '}' || c == ']' -> {
            if (stack.removeLastOrNull() != c) return null
            newline()
            out.append(c)
            if (stack.isEmpty()) done = true
          }
          c == ',' -> {
            if (stack.isEmpty()) return null
            out.append(',')
            newline()
          }
          c == ':' -> out.append(": ")
          c.isWhitespace() -> {}
          else -> {
            if (stack.isEmpty()) return null
            out.append(c)
          }
        }
        i++
      }
      return if (done && stack.isEmpty()) out.toString() else null
    }

    private inline fun String.indexOfFirst(from: Int, predicate: (Char) -> Boolean): Int {
      for (i in from until length) if (predicate(this[i])) return i
      return -1
    }
  }
}

/**
 * The status-line summary of selected cells: how many, how many distinct values, and for numbers (nulls skipped,
 * any other value turns them off) sum, average, minimum and maximum.
 */
class DbAggregates(val count: Int, val distinct: Int, val sum: BigDecimal?, val avg: BigDecimal?, val min: BigDecimal?, val max: BigDecimal?) {
  val numeric: Boolean get() = sum != null

  fun text(): String = buildString {
    append(dev.phantom.db.core.PhantomDbBundle.message("db.grid.aggregates", count, distinct))
    if (sum != null) append(dev.phantom.db.core.PhantomDbBundle.message("db.grid.aggregates.numbers", plain(sum), plain(avg!!), plain(min!!), plain(max!!)))
  }

  companion object {
    fun of(values: List<Any?>): DbAggregates {
      val present = values.filterNotNull()
      // 1, 1.0 and 1.00 are one value
      val distinct = present.map { v -> if (v is ByteArray) v.toList() else (v as? Number)?.let { n -> decimal(n)?.stripTrailingZeros() } ?: v }.toSet().size
      val numbers = present.takeIf { it.isNotEmpty() && it.all { v -> v is Number } }?.mapNotNull { decimal(it as Number) }
      if (numbers == null || numbers.size != present.size) return DbAggregates(values.size, distinct, null, null, null, null)
      val sum = numbers.fold(BigDecimal.ZERO, BigDecimal::add)
      return DbAggregates(values.size, distinct, sum, sum.divide(BigDecimal(numbers.size), MathContext.DECIMAL64), numbers.min(), numbers.max())
    }

    private fun decimal(n: Number): BigDecimal? = when (n) {
      is BigDecimal -> n
      is java.math.BigInteger -> BigDecimal(n)
      is Double -> if (n.isNaN() || n.isInfinite()) null else BigDecimal(n.toString())
      is Float -> if (n.isNaN() || n.isInfinite()) null else BigDecimal(n.toString())
      else -> BigDecimal.valueOf(n.toLong())
    }

    private fun plain(d: BigDecimal): String = d.stripTrailingZeros().toPlainString()
  }
}
