// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.kafka

import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsSpec
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.admin.OffsetSpec
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import java.io.Closeable
import java.io.StringReader
import java.time.Duration
import java.util.Properties
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

data class KafkaTopic(val name: String, val partitions: Int, val internal: Boolean)
data class KafkaPartition(val partition: Int, val leader: String, val replicas: Int, val inSync: Int, val begin: Long, val end: Long)
data class KafkaMessage(val partition: Int, val offset: Long, val timestamp: Long, val key: String?, val value: String?, val headers: List<Pair<String, String>>)
/**
 * What a read got; [unfinishedPartitions] > 0 means it gave up (timeout or cancel) before reaching the end of those.
 * [perPartitionLimit] is set when the requested count per partition was lowered to keep the total under
 * [KafkaSession.MAX_TOTAL_RECORDS].
 */
data class KafkaReadResult(
  val messages: List<KafkaMessage>,
  val unfinishedPartitions: Int,
  val timeoutMs: Long,
  val perPartitionLimit: Int? = null,
) {
  val partial: Boolean get() = unfinishedPartitions > 0
}
/** [error] is set when the group's committed offsets could not be read; the other groups are listed anyway. */
data class KafkaGroupLag(val group: String, val state: String, val topic: String, val lag: Long, val members: Int, val error: String? = null)

/** Where reading starts: the last [count] records of each partition, the first ones, or those after [epochMs]. */
sealed interface KafkaStart {
  data class Latest(val count: Int) : KafkaStart
  data class Earliest(val count: Int) : KafkaStart
  data class Since(val epochMs: Long, val count: Int) : KafkaStart
}

/**
 * Admin, consumer and producer clients for one cluster, created on first use and closed together. Consumers never
 * join a group and never commit: browsing a topic leaves no trace on the cluster. Blocking: call off the EDT.
 */
/** The topic to create; with no [replication], the broker applies its default.replication.factor. */
internal fun newTopic(name: String, partitions: Int, replication: Short?): NewTopic =
  NewTopic(name, java.util.Optional.of(partitions), java.util.Optional.ofNullable(replication))

class KafkaSession(private val bootstrap: String, private val extra: String?) : Closeable {
  private var adminClient: Admin? = null
  private var producerClient: KafkaProducer<ByteArray, ByteArray>? = null

  private fun base(): Properties = Properties().apply {
    extra?.takeIf { it.isNotBlank() }?.let { load(StringReader(it)) }
    put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap)
    put(AdminClientConfig.CLIENT_ID_CONFIG, "phantom-idea")
    putIfAbsent(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "10000")
    putIfAbsent(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "15000")
  }

  /** Kafka loads some classes by name through the context class loader; inside a plugin that must be ours. */
  private fun <T> withPluginLoader(block: () -> T): T {
    val thread = Thread.currentThread()
    val previous = thread.contextClassLoader
    thread.contextClassLoader = KafkaSession::class.java.classLoader
    try {
      return block()
    }
    finally {
      thread.contextClassLoader = previous
    }
  }

  private val admin: Admin get() = adminClient ?: withPluginLoader { Admin.create(base()) }.also { adminClient = it }

  fun topics(includeInternal: Boolean): List<KafkaTopic> {
    val listing = admin.listTopics(org.apache.kafka.clients.admin.ListTopicsOptions().listInternal(includeInternal)).listings().get(15, TimeUnit.SECONDS)
    val names = listing.map { it.name() }
    val described = if (names.isEmpty()) emptyMap() else admin.describeTopics(names).allTopicNames().get(30, TimeUnit.SECONDS)
    return listing.map { KafkaTopic(it.name(), described[it.name()]?.partitions()?.size ?: 0, it.isInternal) }.sortedBy { it.name }
  }

  fun partitions(topic: String): List<KafkaPartition> {
    val description = admin.describeTopics(listOf(topic)).allTopicNames().get(15, TimeUnit.SECONDS)[topic] ?: return emptyList()
    val tps = description.partitions().map { TopicPartition(topic, it.partition()) }
    val begin = admin.listOffsets(tps.associateWith { OffsetSpec.earliest() }).all().get(15, TimeUnit.SECONDS)
    val end = admin.listOffsets(tps.associateWith { OffsetSpec.latest() }).all().get(15, TimeUnit.SECONDS)
    return description.partitions().map { p ->
      val tp = TopicPartition(topic, p.partition())
      KafkaPartition(p.partition(), p.leader()?.let { "${it.host()}:${it.port()} (${it.id()})" } ?: "none", p.replicas().size, p.isr().size,
                     begin[tp]?.offset() ?: 0, end[tp]?.offset() ?: 0)
    }
  }

  /**
   * Reads up to `count` records per partition from [start], newest first, giving up after [timeoutMs] or once
   * [cancelled] returns true; the result says how many partitions were left unfinished.
   */
  fun consume(topic: String, start: KafkaStart, timeoutMs: Long = 8_000, cancelled: () -> Boolean = { false }): KafkaReadResult {
    // a read queued behind another one may be stale before it starts: skip connecting at all
    if (cancelled()) return KafkaReadResult(emptyList(), 0, timeoutMs)
    val props = base().apply {
      put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
      put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "500")
      remove(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG)
    }
    return withPluginLoader { KafkaConsumer(props, ByteArrayDeserializer(), ByteArrayDeserializer()) }.use { consumer ->
      read(consumer, topic, start, timeoutMs, cancelled)
    }
  }

  companion object {
    /** The poll loop of [consume], on any consumer so tests can drive it with a MockConsumer. */
    internal fun read(consumer: Consumer<ByteArray, ByteArray>, topic: String, start: KafkaStart, timeoutMs: Long, cancelled: () -> Boolean): KafkaReadResult {
      val tps = consumer.partitionsFor(topic, Duration.ofSeconds(10)).map { TopicPartition(topic, it.partition()) }
      if (tps.isEmpty()) return KafkaReadResult(emptyList(), 0, timeoutMs)
      consumer.assign(tps)
      val begin = consumer.beginningOffsets(tps)
      val end = consumer.endOffsets(tps)
      val requested = when (start) { is KafkaStart.Latest -> start.count; is KafkaStart.Earliest -> start.count; is KafkaStart.Since -> start.count }
      // count is per partition: on a topic with many partitions it would otherwise hold partitions x count records in memory
      val count = minOf(requested, maxOf(1, MAX_TOTAL_RECORDS / tps.size))
      val from = startOffsets(start, count, tps, begin, end) { epochMs ->
        consumer.offsetsForTimes(tps.associateWith { epochMs }).mapValues { it.value?.offset() }
      }
      val until = tps.associateWith { minOf(end.getValue(it), from.getValue(it) + count) }
      val pending = tps.filter { from.getValue(it) < until.getValue(it) }.toMutableSet()
      for (tp in tps) consumer.seek(tp, from.getValue(tp))
      consumer.pause(tps - pending)
      val out = ArrayList<KafkaMessage>()
      val deadline = System.currentTimeMillis() + timeoutMs
      while (pending.isNotEmpty() && System.currentTimeMillis() < deadline && !cancelled()) {
        for (record in consumer.poll(Duration.ofMillis(500))) {
          val tp = TopicPartition(record.topic(), record.partition())
          if (record.offset() >= until.getValue(tp)) continue
          out.add(KafkaMessage(record.partition(), record.offset(), record.timestamp(), record.key()?.let(KafkaText::display),
                               record.value()?.let(KafkaText::display), record.headers().map { it.key() to (it.value()?.let(KafkaText::display) ?: "") }))
        }
        for (tp in pending.toList()) {
          if (consumer.position(tp) >= until.getValue(tp)) { pending.remove(tp); consumer.pause(listOf(tp)) }
        }
      }
      // whatever is still pending was cut off by the deadline (or a cancel): the caller must not present this as complete
      return KafkaReadResult(out.sortedWith(compareByDescending<KafkaMessage> { it.timestamp }.thenBy { it.partition }.thenByDescending { it.offset }),
                             pending.size, timeoutMs, count.takeIf { it < requested })
    }

    /** Upper bound on the records one read keeps, over all partitions. */
    const val MAX_TOTAL_RECORDS: Int = 10_000

    /** The first offset to read per partition; [atTime] (asked only for [KafkaStart.Since]) maps to null where no record is that new. */
    internal fun startOffsets(
      start: KafkaStart,
      count: Int,
      tps: Collection<TopicPartition>,
      begin: Map<TopicPartition, Long>,
      end: Map<TopicPartition, Long>,
      atTime: (Long) -> Map<TopicPartition, Long?>,
    ): Map<TopicPartition, Long> = when (start) {
      is KafkaStart.Latest -> tps.associateWith { maxOf(begin.getValue(it), end.getValue(it) - count) }
      is KafkaStart.Earliest -> tps.associateWith { begin.getValue(it) }
      is KafkaStart.Since -> atTime(start.epochMs).let { found -> tps.associateWith { found[it] ?: end.getValue(it) } }
    }

    /** One row per group and topic with the summed lag; a group whose offsets failed gets one row with the error. */
    internal fun groupLags(
      ids: List<String>,
      states: Map<String, Pair<String, Int>>,
      committed: Map<String, Result<Map<TopicPartition, Long>>>,
      ends: Map<TopicPartition, Long>,
    ): List<KafkaGroupLag> {
      val out = ArrayList<KafkaGroupLag>()
      for (id in ids.sorted()) {
        val (state, members) = states[id] ?: ("UNKNOWN" to 0)
        val offsets = committed[id]?.getOrElse { e ->
          val cause = if (e is ExecutionException) e.cause ?: e else e
          out.add(KafkaGroupLag(id, state, "", 0, members, cause.message ?: cause.javaClass.simpleName))
          null
        } ?: continue
        if (offsets.isEmpty()) {
          out.add(KafkaGroupLag(id, state, "", 0, members)); continue
        }
        offsets.entries.groupBy { it.key.topic() }.toSortedMap().forEach { (topic, entries) ->
          val lag = entries.sumOf { (tp, offset) -> maxOf(0, (ends[tp] ?: 0) - offset) }
          out.add(KafkaGroupLag(id, state, topic, lag, members))
        }
      }
      return out
    }
  }

  fun produce(topic: String, key: String?, value: String, headers: List<Pair<String, String>>, partition: Int?): Pair<Int, Long> {
    val producer = producerClient ?: withPluginLoader {
      KafkaProducer(base().apply {
        put(ProducerConfig.ACKS_CONFIG, "all")
        put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "15000")
        put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "10000")
        put(ProducerConfig.LINGER_MS_CONFIG, "0")
        remove(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG)
      }, ByteArraySerializer(), ByteArraySerializer())
    }.also { producerClient = it }
    val record = ProducerRecord(topic, partition, key?.toByteArray(), value.toByteArray(),
                                headers.map { RecordHeader(it.first, it.second.toByteArray()) })
    val metadata = producer.send(record).get(20, TimeUnit.SECONDS)
    return metadata.partition() to metadata.offset()
  }

  fun groups(): List<KafkaGroupLag> {
    val listings = admin.listConsumerGroups().all().get(15, TimeUnit.SECONDS)
    if (listings.isEmpty()) return emptyList()
    val ids = listings.map { it.groupId() }
    val states = admin.describeConsumerGroups(ids).all().get(15, TimeUnit.SECONDS)
      .mapValues { (_, d) -> (d.state()?.toString() ?: "UNKNOWN") to d.members().size }
    // One request for all groups instead of one per group; a group that fails (no access, coordinator moving) gets a
    // row with its error instead of failing the whole list.
    val offsets = admin.listConsumerGroupOffsets(ids.associateWith { ListConsumerGroupOffsetsSpec() })
    val committed = ids.associateWith { id ->
      runCatching {
        offsets.partitionsToOffsetAndMetadata(id).get(15, TimeUnit.SECONDS).filterValues { it != null }.mapValues { it.value.offset() }
      }
    }
    val tps = committed.values.flatMap { it.getOrNull()?.keys.orEmpty() }.toSet()
    val ends = if (tps.isEmpty()) {
      emptyMap()
    }
    else {
      val result = admin.listOffsets(tps.associateWith { OffsetSpec.latest() })
      // Per partition, so a deleted topic does not drop every group's lag.
      tps.mapNotNull { tp -> runCatching { tp to result.partitionResult(tp).get(15, TimeUnit.SECONDS).offset() }.getOrNull() }.toMap()
    }
    return groupLags(ids, states, committed, ends)
  }

  /** [replication] null: the broker's default.replication.factor. */
  fun createTopic(name: String, partitions: Int, replication: Short?) {
    admin.createTopics(listOf(newTopic(name, partitions, replication))).all().get(30, TimeUnit.SECONDS)
  }

  fun deleteTopic(name: String) {
    admin.deleteTopics(listOf(name)).all().get(30, TimeUnit.SECONDS)
  }

  override fun close() {
    runCatching { producerClient?.close(Duration.ofSeconds(2)) }
    runCatching { adminClient?.close(Duration.ofSeconds(2)) }
    producerClient = null
    adminClient = null
  }
}

object KafkaText {
  /** UTF-8 text as is; anything else (Avro, protobuf) as hex, so the table never shows mojibake. */
  fun display(bytes: ByteArray): String {
    val text = String(bytes, Charsets.UTF_8)
    if (!text.contains('�') && text.none { it < ' ' && it != '\n' && it != '\r' && it != '\t' }) return text
    return "0x" + bytes.take(512).joinToString("") { "%02x".format(it) } + if (bytes.size > 512) "…" else ""
  }

  /** `a=1` lines → headers. */
  fun headers(text: String): List<Pair<String, String>> = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    .map { line -> line.substringBefore('=').trim() to line.substringAfter('=', "").trim() }
}
