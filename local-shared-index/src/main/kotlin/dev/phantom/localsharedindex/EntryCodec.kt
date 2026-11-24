// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localsharedindex

import com.intellij.psi.impl.cache.impl.id.IdEntryToScopeMapImpl
import com.intellij.psi.search.UsageSearchContext
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.SingleEntryFileBasedIndexExtension
import com.intellij.util.indexing.impl.storage.defaultMapExternalizerFor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.abs

/** Turns the key-value map one file produced into bytes for the store and back. */
internal interface EntryCodec<K, V> {
  fun encode(data: Map<K, V>): ByteArray

  fun decode(bytes: ByteArray, inputId: Int): Map<K, V>

  companion object {
    /** The externalizer the platform uses for the forward index of [extension], so every value type it supports works. */
    fun <K, V> of(extension: FileBasedIndexExtension<K, V>): EntryCodec<K, V> {
      if (extension is SingleEntryFileBasedIndexExtension<*>) {
        @Suppress("UNCHECKED_CAST")
        return singleEntry(extension as SingleEntryFileBasedIndexExtension<Any>) as EntryCodec<K, V>
      }
      val externalizer = defaultMapExternalizerFor(extension)
      return object : EntryCodec<K, V> {
        override fun encode(data: Map<K, V>): ByteArray {
          val bytes = ByteArrayOutputStream()
          DataOutputStream(bytes).use { externalizer.save(it, data) }
          return bytes.toByteArray()
        }

        override fun decode(bytes: ByteArray, inputId: Int): Map<K, V> =
          withUnsignedMasks(DataInputStream(ByteArrayInputStream(bytes)).use { externalizer.read(it) })
      }
    }

    /**
     * IdIndexEntryMapExternalizer.read sign-extends the occurrence mask byte, so UsageSearchContext.ANY (255) comes back
     * as -1. The platform reads that form only to diff it against new data; a cache hit is the new data. The same file
     * served from the cache and mapped by the indexer would differ: a verified hit is reported as changed, and two
     * projects indexing one library file at once (both scan it as unindexed) add different values for it.
     */
    private fun <K, V> withUnsignedMasks(data: Map<K, V>): Map<K, V> {
      val ids = data as? IdEntryToScopeMapImpl ?: return data
      if (ids.values.all { it >= 0 }) return data
      val fixed = IdEntryToScopeMapImpl(ids.size)
      for ((entry, mask) in ids) fixed.updateMask(entry.wordHashCode, mask and UsageSearchContext.ANY.toInt())
      @Suppress("UNCHECKED_CAST")
      return fixed as Map<K, V>
    }

    /**
     * One value per file under the key `abs(fileId)` (the rule of `SingleEntryIndexer.map`): the file id is not
     * stored, it is the new file's id on decode. A presence byte tells a stored value from "no value for this file".
     */
    private fun singleEntry(extension: SingleEntryFileBasedIndexExtension<Any>): EntryCodec<Int, Any> {
      val externalizer = extension.valueExternalizer
      return object : EntryCodec<Int, Any> {
        override fun encode(data: Map<Int, Any>): ByteArray {
          val bytes = ByteArrayOutputStream()
          DataOutputStream(bytes).use {
            val value = data.values.firstOrNull()
            it.writeByte(if (value == null) 0 else 1)
            if (value != null) externalizer.save(it, value)
          }
          return bytes.toByteArray()
        }

        override fun decode(bytes: ByteArray, inputId: Int): Map<Int, Any> =
          DataInputStream(ByteArrayInputStream(bytes)).use {
            if (it.readByte().toInt() == 0) emptyMap() else mapOf(abs(inputId) to externalizer.read(it))
          }
      }
    }
  }
}
