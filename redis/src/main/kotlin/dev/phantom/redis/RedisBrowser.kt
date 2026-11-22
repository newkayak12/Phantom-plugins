// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.redis

/** A key with its type and time to live (-1: no expiry). */
data class RedisKey(val name: String, val type: String, val ttlSeconds: Long)

/** What the value viewer shows: rows of cells, or one text. */
sealed interface RedisValue {
  val type: String
  data class Text(override val type: String, val text: String) : RedisValue
  data class Rows(override val type: String, val columns: List<String>, val rows: List<List<String>>, val total: Long) : RedisValue
}

/** The commands behind the key list and the value viewer. Reads at most [limit] keys or items, never `KEYS *`. */
class RedisBrowser(private val connection: RedisConnection, private val limit: Int = 2000) {
  /** `SCAN` with `MATCH` until [limit] keys or the end; the boolean says whether there were more. */
  fun scan(pattern: String): Pair<List<RedisKey>, Boolean> {
    val names = LinkedHashSet<String>()
    var cursor = "0"
    do {
      val reply = connection.command("SCAN", cursor, "MATCH", pattern.ifBlank { "*" }, "COUNT", "1000")
      val parts = (reply as? RedisReply.Array)?.items ?: throw RedisException((reply as? RedisReply.Error)?.message ?: "Unexpected SCAN reply")
      cursor = (parts[0] as RedisReply.Bulk).text
      (parts[1] as RedisReply.Array).items.forEach { names.add((it as RedisReply.Bulk).text) }
    } while (cursor != "0" && names.size < limit)
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
      "string" -> RedisValue.Text(type, RedisText.prettyJson((connection.command("GET", key) as? RedisReply.Bulk)?.let { RedisText.display(it.bytes) } ?: ""))
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
        val entries = (connection.command("XREVRANGE", key, "+", "-", "COUNT", ITEMS.toString()) as? RedisReply.Array)?.items.orEmpty()
        RedisValue.Rows(type, listOf("ID", "Fields"), entries.map { e ->
          val parts = (e as RedisReply.Array).items
          listOf((parts[0] as RedisReply.Bulk).text, bulks(parts[1]).chunked(2).joinToString(", ") { "${it[0]}=${it.getOrElse(1) { "" }}" })
        }, total)
      }
      "none" -> RedisValue.Text(type, "The key does not exist any more")
      else -> RedisValue.Text(type, connection.command("DUMP", key).render())
    }
  }

  private fun scanItems(command: String, key: String): List<String> {
    val out = ArrayList<String>()
    var cursor = "0"
    do {
      val parts = (connection.command(command, key, cursor, "COUNT", "500") as RedisReply.Array).items
      cursor = (parts[0] as RedisReply.Bulk).text
      out.addAll(bulks(parts[1]))
    } while (cursor != "0" && out.size < ITEMS * 2)
    return out
  }

  private fun bulks(reply: RedisReply): List<String> = (reply as? RedisReply.Array)?.items.orEmpty().map {
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

  companion object {
    const val ITEMS: Int = 500
    private const val PIPELINE_KEYS = 500
  }
}
