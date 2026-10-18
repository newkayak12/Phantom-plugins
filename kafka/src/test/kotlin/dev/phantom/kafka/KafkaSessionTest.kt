// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.kafka

import junit.framework.TestCase
import java.net.Socket

class KafkaSessionTest : TestCase() {
  fun testTextAndHeaders() {
    assertEquals("{\"id\":1}", KafkaText.display("{\"id\":1}".toByteArray()))
    assertEquals("0x00010203ff", KafkaText.display(byteArrayOf(0, 1, 2, 3, -1)))
    assertEquals(listOf("traceId" to "abc", "type" to "Reserved"), KafkaText.headers("traceId=abc\n# comment\n type = Reserved \n"))
    assertEquals("{\n  \"a\": [\n    1,\n    2\n  ],\n  \"b\": \"x,y\"\n}", KafkaJson.pretty("{\"a\":[1,2],\"b\":\"x,y\"}"))
  }

  /** Read-only against a broker on localhost:9092, when one runs: no group joined, nothing produced. */
  fun testLocalBrokerReadOnly() {
    if (runCatching { Socket("localhost", 9092).close() }.isFailure) return
    KafkaSession("localhost:9092", null).use { session ->
      // a fresh cluster may have no topics at all; listing must still work
      val topics = session.topics(includeInternal = true)
      session.groups()
      val topic = topics.firstOrNull { !it.internal && it.partitions > 0 } ?: return
      val partitions = session.partitions(topic.name)
      assertEquals(topic.partitions, partitions.size)
      val messages = session.consume(topic.name, KafkaStart.Latest(3))
      val available = partitions.sumOf { minOf(3L, it.end - it.begin) }
      assertEquals(available.toInt(), messages.size)
    }
  }
}
