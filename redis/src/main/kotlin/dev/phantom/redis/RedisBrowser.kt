// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.redis

/** A key with its type and time to live (-1: no expiry). */
data class RedisKey(val name: String, val type: String, val ttlSeconds: Long)

/** What the value viewer shows: rows of cells, or one text. */
sealed interface RedisValue {
  val type: String
  /** [size] is the full length in bytes when [text] shows only its start, else -1. */
  data class Text(override val type: String, val text: String, val size: Long = -1) : RedisValue
  data class Rows(override val type: String, val columns: List<String>, val rows: List<List<String>>, val total: Long) : RedisValue
}

/**
 * The commands behind the key list and the value viewer. Reads at most [limit] keys or items, never `KEYS *`, and
 * stops scanning after [scanCalls] SCAN round trips or [scanMillis] milliseconds.
 */
class RedisBrowser(
  private val connection: RedisConnection,
  private val limit: Int = 2000,
  private val scanCalls: Int = 1000,
  private val scanMillis: Long = 5_000,
) {
  /**
   * `SCAN` with `MATCH` until [limit] keys, the end, the call or time budget, or [cancelled]; the boolean says whether
   * there were more. A pattern matching few keys of a large database otherwise walked the whole keyspace.
   */
  fun scan(pattern: String, cancelled: () -> Boolean = { false }): Pair<List<RedisKey>, Boolean> {
    val names = LinkedHashSet<String>()
    var cursor = "0"
    var calls = 0
    val deadline = System.nanoTime() + scanMillis * 1_000_000
    fun budgetLeft() = calls < scanCalls && System.nanoTime() < deadline && !cancelled()
    do {
      val (next, page) = page(connection.command("SCAN", cursor, "MATCH", pattern.ifBlank { "*" }, "COUNT", "1000"), "SCAN")
      cursor = next
      names.addAll(page)
      calls++
    } while (cursor != "0" && names.size < limit && budgetLeft())
    val keys = names.take(limit).sorted()
    // TYPE and TTL of each key, pipelined in batches: a round trip per batch rather than two per key (4,000 for a
    // full list), which made a remote server slow to list
    val found = keys.chunked(PIPELINE_KEYS).flatMap { batch ->
      val replies = connection.pipeline(batch.flatMap { listOf(listOf("TYPE", it), listOf("TTL", it)) })
      batch.mapIndexed { i, key -> RedisKey(key, simple(replies[2 * i]), integer(replies[2 * i + 1])) }
    }
    return found to (cursor != "0" || names.size > limit)
  }

  fun value(key: String): RedisValue {
    val type = simple(connection.command("TYPE", key))
    return when (type) {
      "string" -> {
        // GET of a string of hundreds of megabytes filled the heap and froze the text area
        val size = integer(connection.command("STRLEN", key))
        val cut = size > MAX_STRING_BYTES
        val reply = if (cut) connection.command("GETRANGE", key, "0", (MAX_STRING_BYTES - 1).toString()) else connection.command("GET", key)
        val text = bulk(reply)?.let { RedisText.display(it) } ?: ""
        if (cut) RedisValue.Text(type, text, size) else RedisValue.Text(type, RedisText.prettyJson(text))
      }
      "hash" -> {
        val total = integer(connection.command("HLEN", key))
        val items = scanItems("HSCAN", key)
        RedisValue.Rows(type, listOf("Field", "Value"), items.chunked(2).map { listOf(it[0], it.getOrElse(1) { "" }) }, total)
      }
      "list" -> {
        val total = integer(connection.command("LLEN", key))
        val items = bulks(connection.command("LRANGE", key, "0", (ITEMS - 1).toString()))
        RedisValue.Rows(type, listOf("Index", "Value"), items.mapIndexed { i, v -> listOf(i.toString(), v) }, total)
      }
      "set" -> {
        val total = integer(connection.command("SCARD", key))
        RedisValue.Rows(type, listOf("Member"), scanItems("SSCAN", key).sorted().map { listOf(it) }, total)
      }
      "zset" -> {
        val total = integer(connection.command("ZCARD", key))
        val items = bulks(connection.command("ZRANGE", key, "0", (ITEMS - 1).toString(), "WITHSCORES"))
        RedisValue.Rows(type, listOf("Member", "Score"), items.chunked(2).map { listOf(it[0], it.getOrElse(1) { "" }) }, total)
      }
      "stream" -> {
        val total = integer(connection.command("XLEN", key))
        val entries = array(connection.command("XREVRANGE", key, "+", "-", "COUNT", ITEMS.toString()))
        RedisValue.Rows(type, listOf("ID", "Fields"), entries.map { e ->
          val parts = array(e)
          listOf(bulk(parts[0])?.let { String(it, Charsets.UTF_8) }.orEmpty(),
                 bulks(parts[1]).chunked(2).joinToString(", ") { "${it[0]}=${it.getOrElse(1) { "" }}" })
        }, total)
      }
      "none" -> RedisValue.Text(type, PhantomRedisBundle.message("value.gone"))
      else -> RedisValue.Text(type, connection.command("DUMP", key).render())
    }
  }

  private fun scanItems(command: String, key: String): List<String> {
    val out = ArrayList<String>()
    var cursor = "0"
    do {
      val (next, page) = page(connection.command(command, key, cursor, "COUNT", "500"), command)
      cursor = next
      out.addAll(page)
    } while (cursor != "0" && out.size < ITEMS * 2)
    return out
  }

  /** The next cursor and the items of a SCAN-family reply. */
  private fun page(reply: RedisReply, command: String): Pair<String, List<String>> {
    val parts = array(reply)
    val cursor = (parts.getOrNull(0) as? RedisReply.Bulk)?.text ?: throw unexpected(command)
    return cursor to bulks(parts.getOrNull(1) ?: throw unexpected(command))
  }

  // An error reply (WRONGTYPE when the key changed type after TYPE, a NOPERM from an ACL) used to end in a
  // ClassCastException; it is shown as the server's message instead.
  private fun array(reply: RedisReply): List<RedisReply> = when (reply) {
    is RedisReply.Array -> reply.items
    is RedisReply.Nil -> emptyList()
    is RedisReply.Error -> throw RedisException(reply.message)
    else -> throw unexpected(reply.render())
  }

  private fun bulk(reply: RedisReply): ByteArray? = when (reply) {
    is RedisReply.Bulk -> reply.bytes
    is RedisReply.Error -> throw RedisException(reply.message)
    else -> null
  }

  private fun bulks(reply: RedisReply): List<String> = array(reply).map {
    when (it) {
      is RedisReply.Bulk -> RedisText.display(it.bytes)
      else -> it.render()
    }
  }

  private fun simple(reply: RedisReply): String = when (reply) {
    is RedisReply.Simple -> reply.text
    is RedisReply.Bulk -> reply.text
    is RedisReply.Error -> throw RedisException(reply.message)
    else -> reply.render()
  }

  private fun integer(reply: RedisReply): Long = when (reply) {
    is RedisReply.Integer -> reply.value
    is RedisReply.Error -> throw RedisException(reply.message)
    else -> -1
  }

  private fun unexpected(what: String) = RedisException(PhantomRedisBundle.message("error.unexpected.reply", what))

  companion object {
    const val ITEMS: Int = 500
    const val MAX_STRING_BYTES: Long = 1L shl 20
    private const val PIPELINE_KEYS = 500
  }
}
