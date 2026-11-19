// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localindex

import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.impl.storage.defaultMapExternalizerFor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Turns the key-value map one file produced into bytes for the store and back. */
internal interface EntryCodec<K, V> {
  fun encode(data: Map<K, V>): ByteArray

  fun decode(bytes: ByteArray, inputId: Int): Map<K, V>

  companion object {
    /** The externalizer the platform uses for the forward index of [extension], so every value type it supports works. */
    fun <K, V> of(extension: FileBasedIndexExtension<K, V>): EntryCodec<K, V> {
      val externalizer = defaultMapExternalizerFor(extension)
      return object : EntryCodec<K, V> {
        override fun encode(data: Map<K, V>): ByteArray {
          val bytes = ByteArrayOutputStream()
          DataOutputStream(bytes).use { externalizer.save(it, data) }
          return bytes.toByteArray()
        }

        override fun decode(bytes: ByteArray, inputId: Int): Map<K, V> =
          DataInputStream(ByteArrayInputStream(bytes)).use { externalizer.read(it) }
      }
    }
  }
}
