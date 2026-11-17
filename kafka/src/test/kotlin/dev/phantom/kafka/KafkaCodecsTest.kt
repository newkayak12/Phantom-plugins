// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.kafka

import junit.framework.TestCase
import org.apache.kafka.common.compress.Compression
import org.apache.kafka.common.record.CompressionType
import org.apache.kafka.common.record.MemoryRecords
import org.apache.kafka.common.record.SimpleRecord
import org.apache.kafka.common.record.TimestampType
import org.apache.kafka.common.record.RecordBatch

/** The codec jars keep only some platforms' native libraries (build.gradle.kts); these are among them. */
class KafkaCodecsTest : TestCase() {
  fun testNativeCodecsLoadAndRoundTrip() {
    for (type in listOf(CompressionType.ZSTD, CompressionType.SNAPPY, CompressionType.LZ4, CompressionType.GZIP)) {
      val value = "phantom ".repeat(200).toByteArray()
      val records = MemoryRecords.withRecords(RecordBatch.CURRENT_MAGIC_VALUE, 0L, Compression.of(type).build(), TimestampType.CREATE_TIME, SimpleRecord(value))
      val read = records.records().single()
      assertEquals(type.name, String(value), Charsets.UTF_8.decode(read.value()).toString())
    }
    // the native libraries themselves, not a Java fallback
    assertTrue(com.github.luben.zstd.util.Native.isLoaded() || run { com.github.luben.zstd.util.Native.load(); com.github.luben.zstd.util.Native.isLoaded() })
    assertNotNull(org.xerial.snappy.Snappy.getNativeLibraryVersion())
    assertNotNull(net.jpountz.lz4.LZ4Factory.nativeInstance())
  }
}
