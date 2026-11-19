// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.logs

enum class LogLevel { TRACE, DEBUG, INFO, WARN, ERROR }

/** One log line as read from a JSON encoder (logstash-logback-encoder, ECS, Log4j2 JsonTemplateLayout, ...). */
class JsonLog(val level: LogLevel?, val text: String)

object LogLines {
  private val TIME = listOf("@timestamp", "timestamp", "time", "ts", "date")
  private val LEVEL = listOf("level", "log.level", "severity", "loglevel")
  private val THREAD = listOf("thread_name", "thread", "process.thread.name", "threadName")
  private val LOGGER = listOf("logger_name", "logger", "log.logger", "loggerName")
  private val MESSAGE = listOf("message", "msg")
  private val STACK = listOf("stack_trace", "stackTrace", "error.stack_trace", "exception", "thrown")
  private val TRACE = listOf("traceId", "trace_id", "trace.id", "X-B3-TraceId")
  private val KNOWN = (TIME + LEVEL + THREAD + LOGGER + MESSAGE + STACK + TRACE).toSet() +
    setOf("@version", "level_value", "spanId", "span_id", "span.id", "X-B3-SpanId", "caller_class_name", "caller_method_name",
      "caller_file_name", "caller_line_number", "endOfBatch", "loggerFqcn", "threadId", "threadPriority", "instant.epochSecond",
      "instant.nanoOfSecond")
  private val METADATA = listOf("ecs.", "process.", "service.", "host.", "event.", "log.origin.", "error.type", "error.message", "tags")

  /** The readable form of a JSON log line, or null when the line is not a JSON object with a message. */
  fun json(line: String): JsonLog? {
    val trimmed = line.trim()
    if (!trimmed.startsWith('{') || !trimmed.endsWith('}')) return null
    val fields = flatten(Json.parse(trimmed) as? Map<*, *> ?: return null)
    val message = pick(fields, MESSAGE) ?: return null
    val level = pick(fields, LEVEL)?.let(::levelOf)
    val text = buildString {
      pick(fields, TIME)?.let { append(it).append(' ') }
      append((level?.name ?: pick(fields, LEVEL) ?: "").padEnd(5)).append(' ')
      pick(fields, THREAD)?.let { append('[').append(it).append("] ") }
      pick(fields, LOGGER)?.let { append(it).append(" : ") }
      append(message)
      // the rest (MDC values such as userId) stays on the line; the encoder's own metadata does not
      val extra = fields.filterKeys { key -> key.removePrefix("mdc.") !in KNOWN && METADATA.none { key.startsWith(it) } }
      pick(fields, TRACE)?.let { append("  traceId=").append(it) }
      for ((key, value) in extra) append("  ").append(key).append('=').append(value)
      pick(fields, STACK)?.let { append('\n').append(it.trimEnd()) }
    }
    return JsonLog(level, text)
  }

  /** The level of a plain text log line: the first level word near its start. */
  fun level(line: String): LogLevel? = LEVEL_WORD.find(line.take(160))?.let { levelOf(it.value) }

  private val LEVEL_WORD = Regex("""(?<![\w.])(TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL|SEVERE)(?![\w.])""")

  private val TRACE_IDS = listOf(
    Regex("""(?:traceId|trace_id|trace\.id|X-B3-TraceId)["']?\s*[=:]\s*["']?([0-9a-fA-F]{16,32})"""),
    // Spring Boot 3.2+ correlation pattern: [traceId-spanId]
    Regex("""\[([0-9a-fA-F]{32})-[0-9a-fA-F]{16}]"""),
    // Spring Cloud Sleuth pattern: [app,traceId,spanId]
    Regex("""\[[\w.-]*,([0-9a-fA-F]{16,32}),[0-9a-fA-F]{16}"""),
  )

  /** The trace ids of a line, each as its range in the line. */
  fun traceIds(line: String): List<IntRange> =
    TRACE_IDS.flatMap { regex -> regex.findAll(line).mapNotNull { it.groups[1]?.range }.toList() }.distinct()

  fun levelOf(text: String): LogLevel? = when (text.trim().uppercase()) {
    "TRACE", "FINEST", "FINER" -> LogLevel.TRACE
    "DEBUG", "FINE" -> LogLevel.DEBUG
    "INFO", "INFORMATION" -> LogLevel.INFO
    "WARN", "WARNING" -> LogLevel.WARN
    "ERROR", "FATAL", "SEVERE", "CRITICAL" -> LogLevel.ERROR
    else -> null
  }

  private fun pick(fields: Map<String, String>, keys: List<String>): String? =
    keys.firstNotNullOfOrNull { key -> fields[key] ?: fields["mdc.$key"] }?.takeIf { it.isNotEmpty() }

  /** Nested objects as dotted keys; arrays and scalars as text. */
  private fun flatten(map: Map<*, *>, prefix: String = "", into: MutableMap<String, String> = LinkedHashMap()): Map<String, String> {
    for ((key, value) in map) {
      val name = prefix + key
      when (value) {
        is Map<*, *> -> flatten(value, "$name.", into)
        null -> {}
        else -> into[name] = value.toString()
      }
    }
    return into
  }
}

/** A small JSON reader for log lines: objects as maps, arrays as lists, numbers kept as written. */
internal object Json {
  fun parse(text: String): Any? = try {
    Reader(text).run { val value = value(); skip(); if (pos == text.length) value else null }
  }
  catch (_: IllegalArgumentException) {
    null
  }

  private class Reader(val s: String) {
    var pos = 0

    fun skip() { while (pos < s.length && s[pos].isWhitespace()) pos++ }

    fun value(): Any? {
      skip()
      require(pos < s.length)
      return when (val c = s[pos]) {
        '{' -> obj()
        '[' -> arr()
        '"' -> str()
        else -> when {
          s.startsWith("true", pos) -> { pos += 4; true }
          s.startsWith("false", pos) -> { pos += 5; false }
          s.startsWith("null", pos) -> { pos += 4; null }
          c == '-' || c.isDigit() -> num()
          else -> throw IllegalArgumentException()
        }
      }
    }

    fun obj(): Map<String, Any?> {
      val map = LinkedHashMap<String, Any?>()
      pos++
      skip()
      if (s.getOrNull(pos) == '}') { pos++; return map }
      while (true) {
        skip()
        require(s.getOrNull(pos) == '"')
        val key = str()
        skip()
        require(s.getOrNull(pos) == ':')
        pos++
        map[key] = value()
        skip()
        when (s.getOrNull(pos)) {
          ',' -> pos++
          '}' -> { pos++; return map }
          else -> throw IllegalArgumentException()
        }
      }
    }

    fun arr(): List<Any?> {
      val list = ArrayList<Any?>()
      pos++
      skip()
      if (s.getOrNull(pos) == ']') { pos++; return list }
      while (true) {
        list.add(value())
        skip()
        when (s.getOrNull(pos)) {
          ',' -> pos++
          ']' -> { pos++; return list }
          else -> throw IllegalArgumentException()
        }
      }
    }

    fun num(): String {
      val start = pos
      while (pos < s.length && (s[pos].isDigit() || s[pos] in "+-.eE")) pos++
      return s.substring(start, pos)
    }

    fun str(): String {
      pos++
      val out = StringBuilder()
      while (true) {
        require(pos < s.length)
        when (val c = s[pos++]) {
          '"' -> return out.toString()
          '\\' -> {
            require(pos < s.length)
            when (val e = s[pos++]) {
              'n' -> out.append('\n')
              't' -> out.append('\t')
              'r' -> out.append('\r')
              'b' -> out.append('\b')
              'f' -> out.append('\u000c')
              'u' -> {
                require(pos + 4 <= s.length)
                out.append(s.substring(pos, pos + 4).toIntOrNull(16)?.toChar() ?: throw IllegalArgumentException())
                pos += 4
              }
              else -> out.append(e)
            }
          }
          else -> out.append(c)
        }
      }
    }
  }
}
