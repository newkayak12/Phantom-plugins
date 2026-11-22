// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.redis

import junit.framework.TestCase
import java.io.ByteArrayInputStream
import java.net.Socket

class RedisClientTest : TestCase() {
  private fun read(raw: String): RedisReply = RespReader(ByteArrayInputStream(raw.toByteArray())).read()

  fun testParsesRespTwoAndThree() {
    assertEquals(RedisReply.Simple("OK"), read("+OK\r\n"))
    assertEquals(RedisReply.Error("WRONGTYPE Operation"), read("-WRONGTYPE Operation\r\n"))
    assertEquals(RedisReply.Integer(42), read(":42\r\n"))
    assertEquals(RedisReply.Nil, read("$-1\r\n"))
    assertEquals(RedisReply.Bulk("héllo".toByteArray()), read("$6\r\nhéllo\r\n"))
    assertEquals(RedisReply.Array(listOf(RedisReply.Bulk("a".toByteArray()), RedisReply.Integer(1))), read("*2\r\n$1\r\na\r\n:1\r\n"))
    assertEquals(RedisReply.Map(listOf(RedisReply.Simple("k") to RedisReply.Integer(1))), read("%1\r\n+k\r\n:1\r\n"))
    assertEquals(RedisReply.Nil, read("_\r\n"))
  }

  fun testRendersLikeRedisCli() {
    assertEquals("1) \"a\"\n2) (integer) 1\n3) 1) \"x\"\n   2) (nil)", read("*3\r\n$1\r\na\r\n:1\r\n*2\r\n$1\r\nx\r\n$-1\r\n").render())
    assertEquals("(empty array)", read("*0\r\n").render())
    assertEquals("\"\\xac\\xed\\x00\\x05\"", RedisReply.Bulk(byteArrayOf(0xAC.toByte(), 0xED.toByte(), 0, 5)).render())
  }

  fun testSplitsCommandLines() {
    assertEquals(listOf("SET", "greeting", "hello world", "EX", "10"), RedisText.split("SET greeting \"hello world\" EX 10"))
    assertEquals(listOf("SET", "k", "a\nb", "single quoted"), RedisText.split("SET k \"a\\nb\" 'single quoted'"))
    assertEquals(listOf("GET", "user:{1}"), RedisText.split("  GET   user:{1} "))
  }

  fun testPrettyJson() {
    assertEquals("{\n  \"a\": 1,\n  \"b\": [\n    \"x, y\",\n    {}\n  ]\n}", RedisText.prettyJson("{\"a\":1,\"b\":[\"x, y\",{}]}"))
    assertEquals("not json", RedisText.prettyJson("not json"))
    assertEquals("{\"broken\"", RedisText.prettyJson("{\"broken\""))
  }

  /** The key list pipelines TYPE and TTL: a fake server answers each command it reads, in order. */
  fun testScanPipelinesTypeAndTtl() {
    val received = java.util.Collections.synchronizedList(mutableListOf<List<String>>())
    java.net.ServerSocket(0).use { server ->
      val serving = Thread {
        server.accept().use { s ->
          val input = java.io.BufferedReader(java.io.InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
          val out = s.getOutputStream()
          while (true) {
            val head = input.readLine() ?: break
            val args = List(head.removePrefix("*").toInt()) { input.readLine(); input.readLine() }
            received.add(args)
            out.write(when (args[0]) {
              "SCAN" -> "*2\r\n$1\r\n0\r\n*2\r\n$1\r\nb\r\n$1\r\na\r\n"
              "TYPE" -> if (args[1] == "a") "+string\r\n" else "+hash\r\n"
              "TTL" -> if (args[1] == "a") ":-1\r\n" else ":10\r\n"
              else -> "-ERR unexpected\r\n"
            }.toByteArray())
            out.flush()
          }
        }
      }.apply { isDaemon = true; start() }
      RedisConnection.open("localhost", server.localPort, null, null, 0, false).use { c ->
        val (keys, more) = RedisBrowser(c).scan("*")
        assertEquals(listOf(RedisKey("a", "string", -1), RedisKey("b", "hash", 10)), keys)
        assertFalse(more)
      }
      serving.join(5_000)
    }
    assertEquals(listOf("SCAN", "TYPE", "TTL", "TYPE", "TTL"), received.map { it[0] })
  }

  /** Read-only commands against a Redis on localhost:6379, when one runs. */
  fun testLocalServerReadOnly() {
    val up = runCatching { Socket("localhost", 6379).close() }.isSuccess
    if (!up) return
    RedisConnection.open("localhost", 6379, null, null, 0, false).use { c ->
      assertEquals(RedisReply.Simple("PONG"), c.command("PING"))
      val (keys, _) = RedisBrowser(c, limit = 50).scan("*")
      keys.firstOrNull()?.let { key ->
        val value = RedisBrowser(c).value(key.name)
        assertEquals(key.type, value.type)
      }
      assertTrue(c.command("INFO", "server").let { it is RedisReply.Bulk && it.text.contains("redis_version") })
    }
  }
}
