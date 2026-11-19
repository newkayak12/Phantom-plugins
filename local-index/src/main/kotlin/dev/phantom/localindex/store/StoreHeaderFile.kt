// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localindex.store

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32

/** The `header` file of an index directory: magic, the [StoreHeader] fields, then a crc32 of all of that. */
internal object StoreHeaderFile {
  const val NAME = "header"
  private const val MAGIC = 0x4C494448

  /** The stored header, or null if the file is missing, damaged or unreadable. */
  fun read(file: Path): StoreHeader? {
    val bytes = try {
      Files.readAllBytes(file)
    }
    catch (_: IOException) {
      return null
    }
    if (bytes.size < Int.SIZE_BYTES) return null
    val body = bytes.copyOf(bytes.size - Int.SIZE_BYTES)
    if (crc(body) != DataInputStream(bytes.inputStream(body.size, Int.SIZE_BYTES)).readInt()) return null
    return try {
      DataInputStream(body.inputStream()).use {
        if (it.readInt() == MAGIC) StoreHeader(it.readInt(), it.readInt(), it.readInt(), it.readUTF(), it.readLong()) else null
      }
    }
    catch (_: IOException) {
      null
    }
  }

  fun write(file: Path, header: StoreHeader) {
    val out = ByteArrayOutputStream()
    DataOutputStream(out).use {
      it.writeInt(MAGIC)
      it.writeInt(header.formatVersion)
      it.writeInt(header.indexVersion)
      it.writeInt(header.hashVersion)
      it.writeUTF(header.layoutId)
      it.writeLong(header.fingerprint)
      it.writeInt(crc(out.toByteArray()))
    }
    val tmp = file.resolveSibling("$NAME.tmp")
    Files.write(tmp, out.toByteArray())
    Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
  }

  private fun crc(bytes: ByteArray): Int = CRC32().apply { update(bytes) }.value.toInt()
}
