// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localsharedindex.store

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32

data class StoreHeader(val formatVersion: Int, val indexVersion: Int, val hashVersion: Int, val layoutId: String, val fingerprint: Long)

class StoredEntry(val payload: ByteArray, val mapMicros: Int)

/** A copy of index key bytes and their [KeyMap] fingerprint. */
internal class KeyBytes(bytes: ByteArray) {
  val bytes: ByteArray = bytes.copyOf()
  val fingerprint: Long = KeyMap.fingerprint(this.bytes)
}

/** Where a record starts: its segment generation, byte offset, and total length (record header plus payload). */
internal data class Location(val generation: Long, val offset: Long, val length: Int)

/**
 * One append-only file of records: `int magic`, 20-byte key, `int payloadLength`, `int mapMicros`,
 * `int crc32(key, mapMicros, payload)`, payload. Reads are positional, so any thread may read while the one writer
 * thread appends.
 */
internal class Segment(val generation: Long, val path: Path, val indexDir: String) {
  @Volatile private var channel: FileChannel = openChannel()

  @Volatile var retired = false
    private set

  val size = AtomicLong(channel.size())

  private fun openChannel(create: Boolean = true): FileChannel =
    if (create) FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
    else FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)

  /** A thread interrupt closes a shared channel for everybody; reopen it unless the segment was deleted. */
  @Synchronized
  private fun reopen() {
    if (!retired && !channel.isOpen) channel = openChannel(create = false) // never recreates a deleted file
  }

  /** Appends [record]; returns its offset. The size only advances after a full write, so a failed write is overwritten. */
  fun append(record: ByteBuffer): Long {
    val offset = size.get()
    var attempt = 0
    while (true) {
      try {
        val buf = record.duplicate()
        var pos = offset
        while (buf.hasRemaining()) pos += channel.write(buf, pos)
        break
      }
      catch (e: ClosedChannelException) {
        if (retired || attempt++ > 0) throw e
        reopen()
      }
    }
    size.set(offset + record.remaining())
    return offset
  }

  /**
   * The verified entry at [offset]; null if the record is torn, mismatched or fails its checksum (the caller drops
   * the key). Throws [IOException] when the file could not be read at all (closed by an interrupt, I/O error): that
   * says nothing about the record, so the caller must keep the key.
   */
  fun read(key: ByteArray, offset: Long, length: Int): StoredEntry? {
    if (retired || length < RECORD_HEADER_BYTES) return null
    val buf = ByteBuffer.allocate(length)
    // An interrupted thread would make the channel close itself, for every reader; read with the flag cleared.
    val interrupted = Thread.interrupted()
    try {
      if (!readWithReopen(buf, offset)) return null
    }
    finally {
      if (interrupted) Thread.currentThread().interrupt()
    }
    buf.flip()
    if (buf.getInt() != MAGIC) return null
    val storedKey = ByteArray(KEY_BYTES).also { buf.get(it) }
    val payloadLength = buf.getInt()
    val mapMicros = buf.getInt()
    val crc = buf.getInt()
    if (!storedKey.contentEquals(key) || payloadLength != length - RECORD_HEADER_BYTES) return null
    val payload = ByteArray(payloadLength).also { buf.get(it) }
    return if (crc(key, mapMicros, payload) == crc) StoredEntry(payload, mapMicros) else null
  }

  private fun readWithReopen(buf: ByteBuffer, offset: Long): Boolean {
    try {
      return readFully(buf, offset)
    }
    catch (_: ClosedChannelException) {
      // another thread's interrupt closed the shared channel: reopen and try once more
      if (retired) throw ClosedChannelException()
      reopen()
      buf.clear()
      return readFully(buf, offset)
    }
  }

  /** Cuts the file to [end] (the end of the last good record) so later appends follow valid data. */
  fun truncate(end: Long) {
    channel.truncate(end)
    size.set(end)
  }

  /**
   * Reports every complete record header in order; stops at the first bad or torn one. Payloads are not read.
   * Returns the offset where the valid records end.
   */
  fun scan(onRecord: (KeyBytes, Location) -> Unit): Long {
    val fileSize = size.get()
    val header = ByteBuffer.allocate(RECORD_HEADER_BYTES)
    var pos = 0L
    while (pos + RECORD_HEADER_BYTES <= fileSize) {
      header.clear()
      if (!readFully(header, pos)) break
      header.flip()
      if (header.getInt() != MAGIC) break
      val key = ByteArray(KEY_BYTES).also { header.get(it) }
      val payloadLength = header.getInt()
      if (payloadLength < 0 || pos + RECORD_HEADER_BYTES + payloadLength > fileSize) break
      val length = RECORD_HEADER_BYTES + payloadLength
      onRecord(KeyBytes(key), Location(generation, pos, length))
      pos += length
    }
    return pos
  }

  private fun readFully(buf: ByteBuffer, position: Long): Boolean {
    var pos = position
    while (buf.hasRemaining()) {
      val n = channel.read(buf, pos)
      if (n < 0) return false
      pos += n
    }
    return true
  }

  /** Stops further reads and writes and closes the file; the caller deletes it. */
  @Synchronized
  fun retire() {
    retired = true
    try {
      channel.close()
    }
    catch (_: IOException) {
    }
  }

  companion object {
    const val MAGIC = 0x4C494458
    const val KEY_BYTES = 20

    /** magic, key, payload length, mapMicros, crc */
    const val RECORD_HEADER_BYTES = 4 + KEY_BYTES + 4 + 4 + 4

    fun crc(key: ByteArray, mapMicros: Int, payload: ByteArray): Int {
      val crc = CRC32()
      crc.update(key)
      crc.update(ByteBuffer.allocate(4).putInt(mapMicros).array())
      crc.update(payload)
      return crc.value.toInt()
    }

    fun encode(key: ByteArray, mapMicros: Int, payload: ByteArray): ByteBuffer =
      ByteBuffer.allocate(RECORD_HEADER_BYTES + payload.size).apply {
        putInt(MAGIC)
        put(key)
        putInt(payload.size)
        putInt(mapMicros)
        putInt(crc(key, mapMicros, payload))
        put(payload)
        flip()
      }
  }
}
