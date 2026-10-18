// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
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
