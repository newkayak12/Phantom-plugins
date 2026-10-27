// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.realide.probe

/** Just enough JSON for report.json: maps, lists, strings, numbers, booleans and null. */
internal object ProbeJson {
  fun write(value: Any?, indent: String = ""): String = StringBuilder().also { append(it, value, indent) }.toString()

  private fun append(out: StringBuilder, value: Any?, indent: String) {
    val inner = "$indent  "
    when (value) {
      null -> out.append("null")
      is Boolean, is Int, is Long -> out.append(value.toString())
      is Double -> out.append(if (value.isFinite()) value.toString() else "null")
      is Number -> out.append(value.toString())
      is Map<*, *> -> {
        if (value.isEmpty()) return run { out.append("{}"); Unit }
        out.append("{\n")
        var first = true
        for ((k, v) in value) {
          if (!first) out.append(",\n")
          first = false
          out.append(inner).append(quote(k.toString())).append(": ")
          append(out, v, inner)
        }
        out.append("\n").append(indent).append("}")
      }
      is Iterable<*> -> {
        val items = value.toList()
        if (items.isEmpty()) return run { out.append("[]"); Unit }
        out.append("[\n")
        items.forEachIndexed { i, v ->
          if (i > 0) out.append(",\n")
          out.append(inner)
          append(out, v, inner)
        }
        out.append("\n").append(indent).append("]")
      }
      is Array<*> -> append(out, value.toList(), indent)
      else -> out.append(quote(value.toString()))
    }
  }

  private fun quote(s: String): String = buildString {
    append('"')
    for (c in s) {
      when {
        c == '"' -> append("\\\"")
        c == '\\' -> append("\\\\")
        c == '\n' -> append("\\n")
        c == '\r' -> append("\\r")
        c == '\t' -> append("\\t")
        c < ' ' -> append("\\u%04x".format(c.code))
        else -> append(c)
      }
    }
    append('"')
  }
}
