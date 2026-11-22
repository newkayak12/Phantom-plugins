// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.kafka

import junit.framework.TestCase
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.MockConsumer
import org.apache.kafka.clients.consumer.OffsetResetStrategy
import org.apache.kafka.common.PartitionInfo
import org.apache.kafka.common.TopicPartition
import java.net.Socket

class KafkaSessionTest : TestCase() {
  fun testTextAndHeaders() {
    assertEquals("{\"id\":1}", KafkaText.display("{\"id\":1}".toByteArray()))
    assertEquals("0x00010203ff", KafkaText.display(byteArrayOf(0, 1, 2, 3, -1)))
    assertEquals(listOf("traceId" to "abc", "type" to "Reserved"), KafkaText.headers("traceId=abc\n# comment\n type = Reserved \n"))
    assertEquals("{\n  \"a\": [\n    1,\n    2\n  ],\n  \"b\": \"x,y\"\n}", KafkaJson.pretty("{\"a\":[1,2],\"b\":\"x,y\"}"))
  }

  fun testTopicLeavesReplicationToTheCluster() {
    // NewTopic reports -1 when it carries no replication factor: the broker's default.replication.factor applies
    assertEquals(-1, newTopic("orders", 3, null).replicationFactor().toInt())
    assertEquals(3, newTopic("orders", 3, 3).replicationFactor().toInt())
    assertEquals(3, newTopic("orders", 3, null).numPartitions())
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
      val result = session.consume(topic.name, KafkaStart.Latest(3))
      val available = partitions.sumOf { minOf(3L, it.end - it.begin) }
      assertFalse(result.partial)
      assertEquals(available.toInt(), result.messages.size)
    }
  }

  /** Three partitions with two records each at offsets 0..1; only those listed in [delivered] get their records. */
  private fun consumer(delivered: List<Int>): MockConsumer<ByteArray, ByteArray> {
    val consumer = MockConsumer<ByteArray, ByteArray>(OffsetResetStrategy.NONE)
    val tps = (0..2).map { TopicPartition("t", it) }
    consumer.updatePartitions("t", tps.map { PartitionInfo("t", it.partition(), null, emptyArray(), emptyArray()) })
    consumer.updateBeginningOffsets(tps.associateWith { 0L })
    consumer.updateEndOffsets(tps.associateWith { 2L })
    // records can only be added once the partitions are assigned, which read() does itself
    consumer.schedulePollTask {
      for (p in delivered) for (o in 0L..1L) consumer.addRecord(ConsumerRecord("t", p, o, "k$o".toByteArray(), "v$o".toByteArray()))
    }
    return consumer
  }

  fun testCompleteReadIsNotPartial() {
    val result = KafkaSession.read(consumer(listOf(0, 1, 2)), "t", KafkaStart.Latest(5), 2_000) { false }
    assertFalse(result.partial)
    assertEquals(0, result.unfinishedPartitions)
    assertEquals(6, result.messages.size)
  }

  fun testReadCutOffByTimeoutIsPartial() {
    val result = KafkaSession.read(consumer(listOf(1)), "t", KafkaStart.Latest(5), 300) { false }
    assertTrue(result.partial)
    assertEquals(2, result.unfinishedPartitions)
    assertEquals(2, result.messages.size)
    assertEquals("No messages read yet: 2 partitions not finished within 0 s" to "2 messages (partial: 2 partitions not finished within 0 s)",
                 KafkaReads.describe(result))
    assertEquals("No messages read yet: 1 partition not finished within 8 s" to "No messages read yet: 1 partition not finished within 8 s",
                 KafkaReads.describe(KafkaReadResult(emptyList(), 1, 8_000)))
    assertEquals("No messages" to "0 messages", KafkaReads.describe(KafkaReadResult(emptyList(), 0, 8_000)))
  }

  fun testCancelStopsTheReadEarly() {
    val began = System.currentTimeMillis()
    val result = KafkaSession.read(consumer(emptyList()), "t", KafkaStart.Latest(5), 60_000) { System.currentTimeMillis() - began > 200 }
    assertTrue(System.currentTimeMillis() - began < 10_000)
    assertEquals(3, result.unfinishedPartitions)
  }

  fun testStaleReadOfAnotherTopicIsDropped() {
    val reads = KafkaReads()
    val a = reads.start("A")
    // the user picks B: the table is cleared and A's read invalidated
    reads.invalidate()
    val b = reads.start("B")
    assertTrue(a.cancelled)
    assertFalse(reads.isCurrent(a, "B"))
    assertTrue(reads.isCurrent(b, "B"))
    // a newer read of the same topic also supersedes the older one; a result for another selection is never shown
    val b2 = reads.start("B")
    assertFalse(reads.isCurrent(b, "B"))
    assertFalse(reads.isCurrent(b2, "A"))
    reads.invalidate()
    assertFalse(reads.isCurrent(b2, "B"))
  }

  fun testTopicChangeDropsStalePartitions() {
    val loads = KafkaTopicLoads()
    val a = loads.partitions.start("A")
    // the user picks B while A's partitions are still being described
    loads.invalidate()
    assertFalse(loads.partitions.isCurrent(a, "B"))
    assertFalse(loads.partitions.isCurrent(a, "A"))
    val b = loads.partitions.start("B")
    assertTrue(loads.partitions.isCurrent(b, "B"))
    // the two tabs do not cancel each other
    val m = loads.messages.start("B")
    assertTrue(loads.partitions.isCurrent(b, "B"))
    assertTrue(loads.messages.isCurrent(m, "B"))
  }
}
