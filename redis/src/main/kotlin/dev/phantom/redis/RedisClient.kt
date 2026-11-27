// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.redis

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** A RESP2/RESP3 reply. */
sealed interface RedisReply {
  data class Simple(val text: String) : RedisReply
  data class Error(val message: String) : RedisReply
  data class Integer(val value: Long) : RedisReply
  /** Bulk strings stay bytes: values are often binary (serialized Java objects, protobuf). */
  class Bulk(val bytes: ByteArray) : RedisReply {
    val text: String get() = String(bytes, Charsets.UTF_8)
    override fun equals(other: Any?) = other is Bulk && other.bytes.contentEquals(bytes)
    override fun hashCode() = bytes.contentHashCode()
    override fun toString() = "Bulk($text)"
  }
  data object Nil : RedisReply
  data class Array(val items: List<RedisReply>) : RedisReply
  data class Map(val entries: List<Pair<RedisReply, RedisReply>>) : RedisReply

  /** The reply as redis-cli prints it. */
  fun render(indent: String = ""): String = when (this) {
    is Simple -> text
    is Error -> "(error) $message"
    is Integer -> "(integer) $value"
    is Bulk -> "\"" + RedisText.escape(bytes) + "\""
    Nil -> "(nil)"
    is Array -> if (items.isEmpty()) "(empty array)" else items.withIndex().joinToString("\n") { (i, item) ->
      val prefix = if (i == 0) "${i + 1}) " else "$indent${i + 1}) "
      prefix + item.render(indent + " ".repeat("${i + 1}) ".length))
    }
    is Map -> if (entries.isEmpty()) "(empty hash)" else entries.withIndex().joinToString("\n") { (i, e) ->
      (if (i == 0) "" else indent) + "${i + 1}# ${e.first.render()} => ${e.second.render(indent + "   ")}"
    }
  }
}

object RedisText {
  /** Text when the bytes are UTF-8, else the `\xNN` form. */
  fun display(bytes: ByteArray): String {
    val text = String(bytes, Charsets.UTF_8)
    return if (text.contains('\uFFFD') || text.any { it < ' ' && it != '\n' && it != '\r' && it != '\t' }) escape(bytes) else text
  }

  /** A JSON object or array indented by two spaces; anything else unchanged. Strings are copied as they are. */
  fun prettyJson(text: String): String {
    val t = text.trim()
    if (!(t.startsWith("{") && t.endsWith("}")) && !(t.startsWith("[") && t.endsWith("]"))) return text
    val sb = StringBuilder()
    var depth = 0
    var inString = false
    var escaped = false
    fun newline() { sb.append('\n'); repeat(depth) { sb.append("  ") } }
    for (i in t.indices) {
      val c = t[i]
      if (inString) {
        sb.append(c)
        if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false
        continue
      }
      when (c) {
        '"' -> { inString = true; sb.append(c) }
        '{', '[' -> {
          val close = if (c == '{') '}' else ']'
          val next = t.substring(i + 1).trimStart().firstOrNull()
          sb.append(c)
          depth++
          if (next != close) newline()
        }
        '}', ']' -> {
          depth--
          if (sb.isNotEmpty() && sb.last() != '{' && sb.last() != '[') newline()
          sb.append(c)
        }
        ',' -> { sb.append(c); newline() }
        ':' -> sb.append(": ")
        else -> if (!c.isWhitespace()) sb.append(c)
      }
      if (depth < 0) return text
    }
    return if (depth == 0 && !inString) sb.toString() else text
  }

  /** Printable UTF-8 as is, other bytes as `\xNN`, like redis-cli. */
  fun escape(bytes: ByteArray): String {
    val text = String(bytes, Charsets.UTF_8)
    if (!text.contains('\uFFFD')) return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
    return bytes.joinToString("") { b ->
      val c = b.toInt() and 0xFF
      if (c in 0x20..0x7E && c != '"'.code && c != '\\'.code) c.toChar().toString() else "\\x%02x".format(c)
    }
  }

  /** Splits a command line the way redis-cli does: spaces separate, quotes group, `\n` and `\xNN` work in double quotes. */
  fun split(line: String): List<String> = splitBytes(line).map { String(it, Charsets.UTF_8) }

  /**
   * The arguments of [line] as the bytes to send. `\xNN` is the single byte NN, not the character U+00NN encoded as
   * UTF-8 (two bytes from 0x80 on); a `\x` without two hex digits stays `x`, as in redis-cli, instead of throwing.
   */
  fun splitBytes(line: String): List<ByteArray> {
    val out = ArrayList<ByteArray>()
    var i = 0
    while (i < line.length) {
      while (i < line.length && line[i].isWhitespace()) i++
      if (i >= line.length) break
      val arg = Argument()
      i = when (line[i]) {
        '"' -> doubleQuoted(line, i + 1, arg)
        '\'' -> {
          val end = line.indexOf('\'', i + 1).let { if (it < 0) line.length else it }
          arg.text.append(line, i + 1, end)
          end + 1
        }
        else -> {
          val end = (i until line.length).firstOrNull { line[it].isWhitespace() } ?: line.length
          arg.text.append(line, i, end)
          end
        }
      }
      out.add(arg.bytes())
    }
    return out
  }

  /** Reads a double-quoted argument starting after its quote; returns the index after the closing quote. */
  private fun doubleQuoted(line: String, start: Int, arg: Argument): Int {
    var i = start
    while (i < line.length && line[i] != '"') {
      if (line[i] == '\\' && i + 1 < line.length) {
        i++
        i += escape(line, i, arg)
      }
      else arg.text.append(line[i])
      i++
    }
    return i + 1
  }

  /** Adds the escape whose letter is at [i]; returns how many characters after the letter it used. */
  private fun escape(line: String, i: Int, arg: Argument): Int {
    when (line[i]) {
      'n' -> arg.text.append('\n')
      'r' -> arg.text.append('\r')
      't' -> arg.text.append('\t')
      'x' -> {
        val hex = line.substring(i + 1, minOf(i + 3, line.length))
        if (hex.length == 2 && hex.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) {
          arg.byte(hex.toInt(16))
          return 2
        }
        arg.text.append('x')
      }
      else -> arg.text.append(line[i])
    }
    return 0
  }

  /** An argument's bytes: typed text as UTF-8, `\xNN` escapes as raw bytes. */
  private class Argument {
    val text = StringBuilder()
    private val out = ByteArrayOutputStream()

    fun byte(b: Int) {
      flush()
      out.write(b)
    }

    fun bytes(): ByteArray {
      flush()
      return out.toByteArray()
    }

    private fun flush() {
      out.writeBytes(text.toString().toByteArray(Charsets.UTF_8))
      text.setLength(0)
    }
  }
}

class RedisException(message: String) : IOException(message)

/**
 * One blocking connection speaking RESP over a socket, optionally TLS. Not thread-safe: callers serialize commands
 * (the tool window runs them on one background executor).
 */
class RedisConnection private constructor(private val socket: Socket) : Closeable {
  private val input = BufferedInputStream(socket.getInputStream())
  private val output = BufferedOutputStream(socket.getOutputStream())

  fun command(vararg args: String): RedisReply = command(args.map { it.toByteArray(Charsets.UTF_8) })

  fun command(args: List<ByteArray>): RedisReply {
    output.write("*${args.size}\r\n".toByteArray())
    for (a in args) {
      output.write("$${a.size}\r\n".toByteArray())
      output.write(a)
      output.write(CRLF)
    }
    output.flush()
    return RespReader(input).read()
  }

  /** Sends all [commands] before reading their replies: one round trip instead of one per command. */
  fun pipeline(commands: List<List<String>>): List<RedisReply> {
    for (args in commands) {
      output.write("*${args.size}\r\n".toByteArray())
      for (a in args.map { it.toByteArray(Charsets.UTF_8) }) {
        output.write("$${a.size}\r\n".toByteArray())
        output.write(a)
        output.write(CRLF)
      }
    }
    output.flush()
    val reader = RespReader(input)
    return commands.map { reader.read() }
  }

  override fun close() {
    try { socket.close() } catch (_: IOException) {}
  }
  companion object {
    private val CRLF = "\r\n".toByteArray()

    /** Checks that the server certificate names [host] and sends [host] as SNI, unless it is an IP address. */
    internal fun tlsParameters(parameters: SSLParameters, host: String): SSLParameters = parameters.apply {
      endpointIdentificationAlgorithm = "HTTPS"
      val ip = host.contains(':') || host.all { it.isDigit() || it == '.' }
      // SNIHostName rejects names outside the DNS syntax (an underscore, say); such a host simply goes without SNI
      if (!ip) runCatching { SNIHostName(host) }.getOrNull()?.let { serverNames = listOf(it) }
    }

    fun open(host: String, port: Int, user: String?, password: String?, database: Int, tls: Boolean, timeoutMs: Int = 5_000): RedisConnection {
      // SSLSocketFactory checks neither the certificate's host name nor sends SNI on its own: without the HTTPS
      // endpoint identification any trusted certificate, issued for any host, was accepted.
      val socket = if (tls) (SSLSocketFactory.getDefault().createSocket() as SSLSocket).apply { sslParameters = tlsParameters(sslParameters, host) }
                   else Socket()
      socket.connect(InetSocketAddress(host, port), timeoutMs)
      socket.soTimeout = 30_000
      val connection = RedisConnection(socket)
      try {
        if (!password.isNullOrEmpty()) {
          val reply = if (user.isNullOrEmpty()) connection.command("AUTH", password) else connection.command("AUTH", user, password)
          if (reply is RedisReply.Error) throw RedisException(reply.message)
        }
        if (database != 0) (connection.command("SELECT", database.toString()) as? RedisReply.Error)?.let { throw RedisException(it.message) }
        return connection
      }
      catch (e: Exception) {
        connection.close()
        throw e
      }
    }
  }
}

/**
 * Reads one reply; RESP3 types (`%` maps, `~` sets, `,` doubles, `(` big numbers, `#` booleans, `_` null) are mapped
 * onto RESP2 ones, and `|` attributes are read and dropped.
 */
class RespReader(private val input: InputStream) {
  fun read(): RedisReply {
    val type = input.read()
    if (type < 0) throw RedisException(PhantomRedisBundle.message("error.connection.closed"))
    return when (type.toChar()) {
      '+' -> RedisReply.Simple(line())
      '-' -> RedisReply.Error(line())
      ':' -> RedisReply.Integer(line().toLong())
      ',', '(' -> RedisReply.Simple(line())
      '#' -> RedisReply.Integer(if (line() == "t") 1 else 0)
      '_' -> { line(); RedisReply.Nil }
      '$', '=' -> {
        val length = line().toInt()
        if (length < 0) RedisReply.Nil
        else {
          val bytes = input.readNBytes(length)
          input.readNBytes(2)
          RedisReply.Bulk(bytes)
        }
      }
      '*', '~', '>' -> {
        val count = line().toInt()
        if (count < 0) RedisReply.Nil else RedisReply.Array(List(count) { read() })
      }
      // an attribute map comes before the reply it describes (RESP3 spec); redis-cli does not show it either
      '|' -> {
        repeat(line().toInt() * 2) { read() }
        read()
      }
      '%' -> {
        val count = line().toInt()
        RedisReply.Map(List(count) { read() to read() })
      }
      else -> throw RedisException(PhantomRedisBundle.message("error.unexpected.reply", type.toChar().toString()))
    }
  }

  private fun line(): String {
    val bytes = ByteArrayOutputStream()
    while (true) {
      val c = input.read()
      if (c < 0) throw RedisException(PhantomRedisBundle.message("error.connection.closed"))
      if (c == '\r'.code) {
        input.read()
        return bytes.toString(Charsets.UTF_8)
      }
      bytes.write(c)
    }
  }
}
